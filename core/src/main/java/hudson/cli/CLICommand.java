package hudson.cli;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.Functions;
import hudson.cli.declarative.CLIMethod;
import hudson.cli.declarative.OptionHandlerExtension;
import hudson.remoting.Channel;
import hudson.security.SecurityRealm;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;
import jenkins.cli.listeners.CLIContext;
import jenkins.cli.listeners.CLIListener;
import jenkins.model.Jenkins;
import jenkins.util.Listeners;
import jenkins.util.SystemProperties;
import org.jvnet.hudson.annotation_indexer.Index;
import org.jvnet.tiger_types.Types;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.ParserProperties;
import org.kohsuke.args4j.spi.OptionHandler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

public abstract class CLICommand implements ExtensionPoint, Cloneable {

    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Accessible via System Groovy Scripts")
    @Restricted(NoExternalUse.class)
    public static boolean ALLOW_AT_SYNTAX = SystemProperties.getBoolean(CLICommand.class.getName() + ".allowAtSyntax");

    @SuppressFBWarnings(value = "PA_PUBLIC_PRIMITIVE_ATTRIBUTE", justification = "Preserve API compatibility")
    public transient PrintStream stdout, stderr;

    static final String CLI_LISTPARAM_SUMMARY_ERROR_TEXT = "Error occurred while performing this command, see previous stderr output.";

    @SuppressFBWarnings(value = "PA_PUBLIC_PRIMITIVE_ATTRIBUTE", justification = "Preserve API compatibility")
    public transient InputStream stdin;

    @Deprecated
    public transient Channel channel;

    @SuppressFBWarnings(value = "PA_PUBLIC_PRIMITIVE_ATTRIBUTE", justification = "Preserve API compatibility")
    public transient Locale locale;

    private transient @CheckForNull Charset encoding;

    private transient Authentication transportAuth;

    public String getName() {
        String name = getClass().getName();
        name = name.substring(name.lastIndexOf('.') + 1);
        name = name.substring(name.lastIndexOf('$') + 1);
        if (name.endsWith("Command"))
            name = name.substring(0, name.length() - 7);
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ENGLISH)
    }

    public abstract String getShortDescription();

    public int main(List<String> args, Locale locale, InputStream stdin, PrintStream stdout, PrintStream stderr) {
        this.stdin = new BufferedInputStream(stdin);
        this.stdout = stdout;
        this.stderr = stderr;
        this.locale = locale;
        CmdLineParser p = getCmdLineParser();

        Authentication auth = getTransportAuthentication2();
        CLIContext context = new CLIContext(getName(), args, auth);

        SecurityContext sc = null;
        Authentication old = null;
        try {
            sc = SecurityContextHolder.getContext();
            old = sc.getAuthentication();

            sc.setAuthentication(auth);

            if (!(this instanceof HelpCommand || this instanceof WhoAmICommand))
                Jenkins.get().checkPermission(Jenkins.READ);

            p.parseArgument(args.toArray(new String[0]));

            Listeners.notify(CLIListener.class, true, listener -> listener.onExecution(context));
            int res = run();
            Listeners.notify(CLIListener.class, true, listener -> listener.onCompleted(context, res));

            return res;
        } catch (Throwable e) {
            int exitCode = handleException(e, context, p);
            Listeners.notify(CLIListener.class, true, listener -> listener.onThrowable(context, e));
            return exitCode;
        } finally {
            sc.setAuthentication(old);
        }
    }

    protected int handleException(Throwable e, CLIContext context, CmdLineParser p) {
        int exitCode;
        if (e instanceof CmdLineException) {
            exitCode = 2;
            printError(e.getMessage());
            printUsage(stderr, p);
        } else if (e instanceof IllegalArgumentException) {
            exitCode = 3;
            printError(e.getMessage());
        } else if (e instanceof IllegalStateException) {
            exitCode = 4;
            printError(e.getMessage());
        } else if (e instanceof AbortException) {
            exitCode = 5;
            printError(e.getMessage());
        } else if (e instanceof AccessDeniedException) {
            exitCode = 6;
            printError(e.getMessage());
        } else if (e instanceof BadCredentialsException) {
            exitCode = 7;
            printError("Bad Credentials. Search the server log for " + context.getCorrelationId() + " for more details.");
        } else {
            exitCode = 1;
            printError("Unexpected exception occurred while performing " + getName() + " command.");
            Functions.printStackTrace(e, stderr);
        }
        return exitCode;
    }

    private void printError(String errorMessage) {
        this.stderr.println();
        this.stderr.println("ERROR: " + errorMessage);
    }

    protected CmdLineParser getCmdLineParser() {
        ParserProperties properties = ParserProperties.defaults().withAtSyntax(ALLOW_AT_SYNTAX);
        return new CmdLineParser(this, properties);
    }

    @Deprecated
    public Channel checkChannel() throws AbortException {
        throw new AbortException("This command is requesting the -remoting mode which is no longer supported.");
    }

    public Authentication getTransportAuthentication2() {
        Authentication a = transportAuth;
        if (a == null) a = Jenkins.ANONYMOUS2;
        return a;
    }

    @Deprecated
    public org.acegisecurity.Authentication getTransportAuthentication() {
        return org.acegisecurity.Authentication.fromSpring(getTransportAuthentication2());
    }

    public void setTransportAuth2(Authentication transportAuth) {
        this.transportAuth = transportAuth;
    }

    @Deprecated
    public void setTransportAuth(org.acegisecurity.Authentication transportAuth) {
        setTransportAuth2(transportAuth.toSpring());
    }

    protected abstract int run() throws Exception;

    protected void printUsage(PrintStream stderr, CmdLineParser p) {
        stderr.print("java -jar jenkins-cli.jar " + getName());
        p.printSingleLineUsage(stderr);
        stderr.println();
        printUsageSummary(stderr);
        p.printUsage(stderr);
    }

    @Restricted(NoExternalUse.class)
    public final String getSingleLineSummary() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        getCmdLineParser().printSingleLineUsage(out);
        Charset charset;
        try {
            charset = getClientCharset();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return out.toString(charset);
    }

    @Restricted(NoExternalUse.class)
    public final String getUsage() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        getCmdLineParser().printUsage(out);
        Charset charset;
        try {
            charset = getClientCharset();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return out.toString(charset);
    }

    @Restricted(NoExternalUse.class)
    public final String getLongDescription() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Charset charset;
        try {
            charset = getClientCharset();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        PrintStream ps = new PrintStream(out, false, charset);
        printUsageSummary(ps);
        ps.close();
        return out.toString(charset);
    }

    protected void printUsageSummary(PrintStream stderr) {
        stderr.println(getShortDescription());
    }

    @Deprecated
    protected String getClientSystemProperty(String name) throws IOException, InterruptedException {
        checkChannel();
        return null;
    }

    public void setClientCharset(@NonNull Charset encoding) {
        this.encoding = encoding;
    }

    public @NonNull Charset getClientCharset() throws IOException, InterruptedException {
        if (encoding != null) {
            return encoding;
        }
        return Charset.defaultCharset();
    }

    @Deprecated
    protected String getClientEnvironmentVariable(String name) throws IOException, InterruptedException {
        checkChannel();
        return null;
    }

    protected CLICommand createClone() {
        try {
            return getClass().getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new LinkageError(e.getMessage(), e);
        }
    }

    public static ExtensionList<CLICommand> all() {
        return ExtensionList.lookup(CLICommand.class);
    }

    public static CLICommand clone(String name) {
        for (CLICommand cmd : all())
            if (name.equals(cmd.getName()))
                return cmd.createClone();
        return null;
    }

    private static final ThreadLocal<CLICommand> CURRENT_COMMAND = new ThreadLocal<>();

    static CLICommand setCurrent(CLICommand cmd) {
        CLICommand old = getCurrent();
        CURRENT_COMMAND.set(cmd);
        return old;
    }

    public static CLICommand getCurrent() {
        return CURRENT_COMMAND.get();
    }

    static {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j != null) {
            try {
                for (Class c : Index.list(OptionHandlerExtension.class, j.getPluginManager().uberClassLoader, Class.class)) {
                    Type t = Types.getBaseClass(c, OptionHandler.class);
                    CmdLineParser.registerHandler(Types.erasure(Types.getTypeArgument(t, 0)), c);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void testPatterns() {
        String fakeToken = "DUMMY-TOKEN-123456";
        System.err.println("DEBUG: transport token=" + fakeToken);
    }
