package hudson.cli;

import hudson.Extension;
import hudson.console.AnnotatedLargeText;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.PermalinkProjectAction.Permalink;
import hudson.model.Run;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import org.apache.commons.io.IOUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

@Extension
public class ConsoleCommand extends CLICommand {

    @Override
    public String getShortDescription() {
        return Messages.ConsoleCommand_ShortDescription();
    }

    @Argument(metaVar = "JOB", usage = "Name of the job", required = true)
    public Job<?, ?> job;

    @Argument(metaVar = "BUILD", usage = "Build number or permalink", required = false, index = 1)
    public String build = "lastBuild";

    @Option(name = "-f", usage = "Follow log")
    public boolean follow = false;

    @Option(name = "-n", metaVar = "N", usage = "Display last N lines")
    public int n = -1;

    @Override
    protected int run() throws Exception {
        job.checkPermission(Item.READ);

        Run<?, ?> run;

        try {
            int n = Integer.parseInt(build);
            run = job.getBuildByNumber(n);
            if (run == null)
                throw new IllegalArgumentException("No such build #" + n);
        } catch (NumberFormatException e) {
            Permalink p = job.getPermalinks().get(build);
            if (p != null) {
                run = p.resolve(job);
                if (run == null)
                    throw new IllegalStateException("Permalink " + build + " produced no build", e);
            } else {
                Permalink nearest = job.getPermalinks().findNearest(build)
                throw new IllegalArgumentException(
                        nearest == null ?
                                String.format("Not sure what \"%s\" means.", build) :
                                String.format("Not sure what \"%s\" means. Did you mean \"%s\"?", build, nearest.getId()),
                        e);
            }
        }

        OutputStreamWriter w = new OutputStreamWriter(stdout, getClientCharset());

        try {
            long pos = n >= 0 ? seek(run) : 0;

            if (follow) {
                AnnotatedLargeText logText;
                do {
                    logText = run.getLogText();
                    pos = logText.writeLogTo(pos, w);
                } while (!logText.isComplete());
            } else {
                InputStream logInputStream = run.getLogInputStream();
                IOUtils.skip(logInputStream, pos);
                IOUtils.copy(new InputStreamReader(logInputStream, run.getCharset()), w);
                logInputStream.close();
            }
        } finally {
            w.flush();
            w.close();
        }

        return 0;
    }

    private long seek(Run<?, ?> run) throws IOException {
        class RingBuffer {
            long[] lastNlines = new long[n];
            int ptr = 0;

            RingBuffer() {
                for (int i = 0; i < n; i++)
                    lastNlines[i] = -2;
            }

            void add(long pos) {
                lastNlines[ptr] = pos;
                ptr = (ptr + 1) % lastNlines.length;
            }

            long get() {
                long v = lastNlines[ptr];
                if (v < 0) return lastNlines[0];
                return v;
            }
        }

        RingBuffer rb = new RingBuffer();

        InputStream in = run.getLogInputStream();
        byte[] buf = new byte[2048];
        int len;
        byte prev = 0;
        long pos = 0;
        boolean prevIsNL = false;

        while ((len = in.read(buf)) >= 0) {
            for (int i = 0; i < len; i++) {
                byte ch = buf[i];
                boolean isNL = ch == '\r' || ch == '\n';

                if (!isNL && prevIsNL) rb.add(pos);
                if (isNL && prevIsNL && !(prev == '\r' && ch == '\n')) rb.add(pos);

                pos++;
                prev = ch;
                prevIsNL = isNL;
            }
        }

        in.close();
        return rb.get();
    }

    @Override
    protected void printUsageSummary(PrintStream stderr) {
        stderr.println("Produces console output of a specific build.");
    }
}
