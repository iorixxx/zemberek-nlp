package zemberek.embedding.fasttext;

import com.google.common.base.Stopwatch;
import zemberek.core.collections.IntVector;
import zemberek.core.io.IOUtil;
import zemberek.core.logging.Log;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;

public class FastText {
    private Args args_;
    private Dictionary dict_;
    private Matrix input_;
    private Matrix output_;
    private Model model_;
    private int tokenCount;

    private Stopwatch stopwatch;


    // Sums all word and ngram vectors for a word and normalizes it.
    Vector getVector(String word) {
        int[] ngrams = dict_.getNgrams(word);
        Vector vec = new Vector(args_.dim);
        for (int i : ngrams) {
            vec.addRow(input_, i);
        }
        if (ngrams.length > 0) {
            vec.mul(1.0f / ngrams.length);
        }
        return vec;
    }

    void saveVectors() throws IOException {
        Path out = Paths.get(args_.output + ".vec");
        try (PrintWriter pw = new PrintWriter(out.toFile(), "utf-8")) {
            pw.println(dict_.nwords() + " " + args_.dim);
            for (int i = 0; i < dict_.nwords(); i++) {
                String word = dict_.getWord(i);
                Vector vector = getVector(word);
                pw.println(word + " " + vector.asString());
            }
        }
    }

    void saveModel() throws IOException {
        Path output = Paths.get(args_.output + ".bin");
        try (DataOutputStream dos = IOUtil.getDataOutputStream(output)) {
            args_.save(dos);
            dict_.save(dos);
            input_.save(dos);
            output_.save(dos);
        }
    }

    void loadModel(Path path) throws IOException {
        try (DataInputStream dis = IOUtil.getDataInputStream(path)) {
            loadModel(dis);
        }
    }

    void loadModel(DataInputStream dis) throws IOException {
        args_ = Args.load(dis);
        dict_ = Dictionary.load(dis, args_);
        input_ = Matrix.load(dis);
        output_ = Matrix.load(dis);
        model_ = new Model(input_, output_, args_, 0);
        if (args_.model == Args.model_name.sup) {
            model_.setTargetCounts(dict_.getCounts(Dictionary.TYPE_LABEL));
        } else {
            model_.setTargetCounts(dict_.getCounts(Dictionary.TYPE_WORD));
        }
    }

    void printInfo(float progress, float loss) {
        float t = stopwatch.elapsed(TimeUnit.MILLISECONDS) / 1000f;
        float wst = (float) tokenCount / t;
        float lr = (float) (args_.lr * (1.0f - progress));
        int eta = (int) (t / progress * (1 - progress) / args_.thread);
        int etah = eta / 3600;
        int etam = (eta - etah * 3600) / 60;

        Log.info("Progress: %.1f%% words/sec/thread: %.1f lr: %.6f loss:%.6f eta %d h %d m",
                100 * progress,
                wst,
                lr,
                loss,
                etah,
                etam);
    }

    void supervised(Model model, float lr,
                    int[] line,
                    int[] labels) {
        if (labels.length == 0 || line.length == 0) return;
        int i = model.random.nextInt(labels.length - 1);
        model.update(line, labels[i], lr);
    }

    void cbow(Model model, float lr, int[] line) {
        for (int w = 0; w < line.length; w++) {
            int boundary = model.random.nextInt(args_.ws - 1) + 1; // [1..args.ws]
            IntVector bow = new IntVector();
            for (int c = -boundary; c <= boundary; c++) {
                if (c != 0 && w + c >= 0 && w + c < line.length) {
                    int[] ngrams = dict_.getNgrams(line[w + c]);
                    bow.addAll(ngrams);
                }
            }
            model.update(bow.copyOf(), line[w], lr);
        }
    }

    void skipgram(Model model, float lr, int[] line) {
        for (int w = 0; w < line.length; w++) {
            int boundary = model.random.nextInt(args_.ws - 1) + 1; // [1..args.ws]
            int[] ngrams = dict_.getNgrams(line[w]);
            for (int c = -boundary; c <= boundary; c++) {
                if (c != 0 && w + c >= 0 && w + c < line.length) {
                    model.update(ngrams, line[w + c], lr);
                }
            }
        }
    }

    static class ScoreStringPair {
        final float score;
        final String string;

        ScoreStringPair(float score, String string) {
            this.score = score;
            this.string = string;
        }
    }

    // TODO: signature is different in original. original has a stream and a list of predictions
    // this one returns the predictions and input is a string representing a line.
    List<ScoreStringPair> predict(String line, int k) {
        IntVector words = new IntVector();
        IntVector labels = new IntVector();
        dict_.getLine(line, words, labels, model_.random);
        dict_.addNgrams(words, args_.wordNgrams);
        if (words.isempty()) {
            return Collections.emptyList();
        }
        Vector hidden = new Vector(args_.dim);
        Vector output = new Vector(dict_.nlabels());
        PriorityQueue<Model.Pair> modelPredictions = new PriorityQueue<>();
        model_.predict(words.copyOf(), k, modelPredictions, hidden, output);
        List<ScoreStringPair> result = new ArrayList<>(modelPredictions.size());
        for (Model.Pair pair : modelPredictions) {
            result.add(new ScoreStringPair(pair.first, dict_.getLabel(pair.second)));
        }
        return result;
    }

    void trainThread(int threadId) {

        //std::ifstream ifs(args_->input);
        //utils::seek(ifs, threadId * utils::size(ifs) / args_->thread);


        Model model = new Model(input_, output_, args_, threadId);
        if (args_.model == Args.model_name.sup) {
            model.setTargetCounts(dict_.getCounts(Dictionary.TYPE_LABEL));
        } else {
            model.setTargetCounts(dict_.getCounts(Dictionary.TYPE_WORD));
        }

        long ntokens = dict_.ntokens();
        long localTokenCount = 0;
        while (tokenCount < args_.epoch * ntokens) {
            //TODO: those are reused in the original. emptied in getLine()
            IntVector line = new IntVector();
            IntVector labels = new IntVector();

            float progress = (float) tokenCount / (args_.epoch * ntokens);
            float lr = (float) args_.lr * (1.0f - progress);
            localTokenCount += dict_.getLine(/* TODO: fix this ifs*/"", line, labels, model.random);
            if (args_.model == Args.model_name.sup) {
                dict_.addNgrams(line, args_.wordNgrams);
                supervised(model, lr, line.copyOf(), labels.copyOf());
            } else if (args_.model == Args.model_name.cbow) {
                cbow(model, lr, line.copyOf());
            } else if (args_.model == Args.model_name.sg) {
                skipgram(model, lr, line.copyOf());
            }
            if (localTokenCount > args_.lrUpdateRate) {
                tokenCount += localTokenCount;
                localTokenCount = 0;
                if (threadId == 0 && args_.verbose > 1) {
                    printInfo(progress, model.getLoss());
                }
            }
        }
        if (threadId == 0 && args_.verbose > 0) {
            printInfo(1.0f, model.getLoss());
        }
        //ifs.close();
    }


    void train(Args args) throws IOException {
        args_ = args;
        if (args_.input.equals("-")) {
            // manage expectations
            Log.error("Cannot use stdin for training!");
            System.exit(-1);
        }
        dict_ = Dictionary.readFromFile(Paths.get(args.input), args);

        if (args_.pretrainedVectors.length() != 0) {
            //TODO: implement this.
            //loadVectors(args_->pretrainedVectors);
        } else {
            input_ = new Matrix(dict_.nwords() + args_.bucket, args_.dim);
            input_.uniform(1.0f / args_.dim);
        }

        if (args_.model == Args.model_name.sup) {
            output_ = new Matrix(dict_.nlabels(), args_.dim);
        } else {
            output_ = new Matrix(dict_.nwords(), args_.dim);
        }

        stopwatch = Stopwatch.createStarted();
        tokenCount = 0;
/*        std::vector<std::thread> threads;
        for (int32_t i = 0; i < args_->thread; i++) {
            threads.push_back(std::thread([=]() { trainThread(i); }));
        }
        for (auto it = threads.begin(); it != threads.end(); ++it) {
            it->join();
        }
        model_ = std::make_shared<Model>(input_, output_, args_, 0);

        saveModel();
        if (args_->model != model_name::sup) {
            saveVectors();
        }*/
    }


}
