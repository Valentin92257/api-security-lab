package lab.control.runner;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class RunHandle {
    private final RunContext context;
    private final CompletionStage<RunResult> completion;

    RunHandle(RunContext context, CompletableFuture<RunResult> completion) {
        this.context = context;
        this.completion = completion.minimalCompletionStage();
    }

    public boolean cancel() {
        return context.cancel();
    }

    public CompletionStage<RunResult> completion() {
        return completion;
    }
}
