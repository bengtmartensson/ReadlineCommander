/*
Copyright (C) 2016 Bengt Martensson.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 3 of the License, or (at
your option) any later version.

This program is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with
this program. If not, see http://www.gnu.org/licenses/.
*/

package org.harctoolbox.readlinecommander;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import gnu.io.NoSuchPortException;
import gnu.io.PortInUseException;
import gnu.io.UnsupportedCommOperationException;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.gnu.readline.Readline;
import org.gnu.readline.ReadlineLibrary;
import org.harctoolbox.harchardware.BufferedExecutor;
import org.harctoolbox.harchardware.FramedDevice;
import org.harctoolbox.harchardware.HarcHardwareException;
import org.harctoolbox.harchardware.ICommandExecutor;
import org.harctoolbox.harchardware.ICommandLineDevice;
import org.harctoolbox.harchardware.Version;
import org.harctoolbox.harchardware.comm.LocalSerialPortBuffered;
import org.harctoolbox.harchardware.comm.TcpSocketPort;
import org.harctoolbox.irp.IrpUtils;

/**
 * This class allows for the bi-directional communication with in interactive command
 * line program, using <a href="https://github.com/bengtmartensson/java-readline.git">Java Readline</a>.
 * It consists of static members only, since the Java Readline was designed that way.
 */
public class ReadlineCommander {

    private static final int defaultPort = 23; // the Telnet port
    private static final int defaultWaitForAnswer = 1000; // milliseconds
    private static final int defaultBaudrate = 115200;
    private static final String defaultConfigFileName = null;
    private static final String defaultHistoryFileName = null;
    private static final String defaultAppName = "ReadlineCommander";
    private static final String defaultPrompt = "RLC> ";

    private static final String versionString = defaultAppName + " 0.1.1";
    private static final String quitName = "quit";
    private static final String sleepName = "sleep";
    private static final String dateName = "date";

    private static String historyFile;
    private static String prompt;
    private static boolean initialized = false;
    private static boolean verbose;
    private static String goodbyeWord;
    private static String escape;
    private static String comment;
    private static PrintStream stdout = null;
    private static PrintStream stderr = null;
    private static JCommander argumentParser;
    private static final CommandLineArgs commandLineArgs = new CommandLineArgs();

    /**
     * @param newStdout the stdout to set
     */
    public static void setStdout(PrintStream newStdout) {
        stdout = newStdout;
    }

    /**
     * @param newStderr the stderr to set
     */
    public static void setStderr(PrintStream newStderr) {
        stderr = newStderr;
    }

    public static void setGoodbyeWord(String word) {
        goodbyeWord = word;
    }

    /**
     * Version of init with defaults.
     * @throws java.io.IOException
     */
    public static void init() throws IOException {
        init(defaultConfigFileName, defaultHistoryFileName, defaultPrompt, defaultAppName);
    }

    /**
     * Initializes readline.
     *
     * @param confFile File name of the configuration file.
     * @param historyFile_ File name of the history file.
     * @param prompt_ Prompt for Readline to use.
     * @param appName appName for readline to use when interpreting its configuration. Must be != null.
     * @throws java.io.IOException
     */
    public static void init(String confFile, String historyFile_, String prompt_, String appName) throws IOException {
        if (stdout == null)
            stdout = System.out;
        if (stderr == null)
            stderr = System.err;

        historyFile = historyFile_;
        prompt = prompt_;

        try {
            Readline.load(ReadlineLibrary.GnuReadline);
            if (verbose)
                stderr.println("Successful load of the Gnu Readline library");
            Readline.initReadline(appName);
            if (confFile != null) {
                if (new File(confFile).exists()) {
                    try {
                        Readline.readInitFile(confFile);
                    } catch (IOException ex) {
                        stderr.println(ex.getMessage());
                    }
                } else {
                    stderr.println("Warning: Cannot open readline configuration " + confFile + ", ignoring");
                }
            }

            if (historyFile != null) {
                if (new File(historyFile).exists()) {
                    try {
                        Readline.readHistoryFile(historyFile);
                    } catch (EOFException | UnsupportedEncodingException ex) {
                        stderr.println("This cannot happen.");
                    }
                } else {
                    stderr.println("Cannot read readline history " + historyFile
                            + ", will try to write it when exiting anyhow.");
                    File parent = new File(historyFile).getParentFile();
                    if (!parent.isDirectory()) {
                        boolean success = parent.mkdirs();
                        if (!success)
                            throw new IOException(parent.getCanonicalPath()
                                    + " is not a directory or could not be created");
                    }
                }
            }
        } catch (UnsatisfiedLinkError ignoreMe) {
            stderr.println("Could not load readline lib. Using simple stdin.");
        }
        initialized = true;
    }

    /**
     * Reads a line, using readline editing, and returns it.
     * @return Line the user typed. Is empty ("") if the user entered an empty line, is null if EOF.
     * @throws IOException if Readline threw it, of if called without calling init(...) first.
     */
    // Readline delivers null for empty line, and throws EOFException for EOF.
    // We repacket this here.
    public static String readline() throws IOException {
        if (!initialized)
            throw new IOException("Readline not initialized");

        String line;
        try {
            line = Readline.readline(prompt, false);
            int size = Readline.getHistorySize();
            if ((line != null && !line.isEmpty())
                    && (size == 0 || !line.equals(Readline.getHistoryLine(size - 1))))
                Readline.addToHistory(line);
        } catch (EOFException ex) {
            return null;
        }
        return line == null ? "" : line;
    }

    /**
     * Closes the history file (if used) and cleans up.
     * @throws IOException IOException
     */
    public static void close() throws IOException {
        if (verbose)
            stderr.println("Closing readline");
        initialized = false;
        if (historyFile != null) {
            if (verbose)
                stderr.println("Writing history file \"" + historyFile + "\"");
            Readline.writeHistoryFile(historyFile);
        }
        Readline.cleanup();
    }

    private static void listen(FramedDevice stringCommander) throws IOException {
        while (true) {
            String line = stringCommander.readString(true);
            stdout.println(line);
        }
    }

    private static String[] evalPrint(FramedDevice stringCommander, int waitForAnswer, int returnlines, String line) throws IOException, HarcHardwareException {
        String[] result = stringCommander.sendString(line, returnlines <= 0 ? -1 : returnlines, waitForAnswer);
        if (result != null) {
            for (String str : result)
                if (str != null)
                    stdout.println(str);
        }
        return result;
    }

    /**
     * Reads a command using readline and sends it to the hardware instance in the first argument.
     * Responses are sent to stdout.
     * This continues until EOF.
     *
     * Catches all "normal" exceptions that appear.
     * Does not detect if the hardware closes the connection :-(
     * @param stringCommander hardware compoent to be controlled.
     * @param waitForAnswer milliseconds to wait for an answer.
     * @param returnlines If &ge; 0 wait for this many return lines. If &lt; 0,
     * takes as many lines that are available within waitForAnswer milliseconds-
     */
    public static void readEvalPrint(FramedDevice stringCommander, int waitForAnswer, int returnlines) {
        try {
            while (stringCommander.ready()) {
                String line = stringCommander.readString(false);
                stdout.println(line);
            }
        } catch (IOException ex) {
            // Probably someting is really bad...
            throw new RuntimeException(ex);
        }

        while (true) {
            String line = null;
            try {
                line = readline();
            } catch (IOException ex) {
                stderr.println(ex.getMessage());
            }

            if (line == null) { // EOF, User press Ctrl-D.
                stdout.println();
                break;
            }

            if (comment != null && line.trim().startsWith(comment))
                continue;

            if (escape != null && line.trim().startsWith(escape)) {
                line = line.trim().substring(escape.length());
                if (line.startsWith(quitName)) {
                    stdout.println();
                    break;
                } else if (line.startsWith(sleepName)) {
                    int millis = 0;
                    try {
                        millis = (int) (1000f * Double.parseDouble(line.substring(sleepName.length()).trim()));
                    } catch (NumberFormatException ex) {
                        stderr.println(ex);
                    }
                    try {
                        Thread.sleep(millis);
                    } catch (InterruptedException | IllegalArgumentException ex) {
                        stderr.println(ex);
                    }
                } else if (line.startsWith(dateName)) {
                    stdout.println("*** Date: " + new Date());
                } else {
                    stderr.println("Unknown escape: " + escape + line);
                }
                continue;
            }

            try {
                if (line.isEmpty() && stringCommander.ready()) {
                    while (stringCommander.ready()) {
                        String pending = stringCommander.readString(false);
                        stdout.println(pending);
                    }
                } else {
                    String[] result = evalPrint(stringCommander, waitForAnswer, returnlines, line);
                    if (result != null && result.length > 0
                            && result[result.length - 1] != null && result[result.length - 1].equals(goodbyeWord))
                        break;
                }
            } catch (IOException | HarcHardwareException ex) {
                stderr.println(ex.getMessage());
            }
        }
        if (verbose)
            stdout.println("Readline.readEvalPrint exited");
    }

    public static void readEvalPrint(ICommandLineDevice hardware, int waitForAnswer, int returnLines) {
        readEvalPrint(new FramedDevice(hardware), waitForAnswer, returnLines);
    }

    public static void readEvalPrint(ICommandExecutor hardware, int waitForAnswer, int returnLines) {
        readEvalPrint(new BufferedExecutor(hardware), waitForAnswer, returnLines);
    }

    private static ICommandLineDevice createCommandLineDevice(CommandLineArgs commandLineArgs) throws UnknownHostException, IOException, HarcHardwareException {
        int timeout = commandLineArgs.listen ? 0 : commandLineArgs.timeout;
        try {
            return commandLineArgs.ip != null
                    ? new TcpSocketPort(commandLineArgs.ip, commandLineArgs.port,
                            timeout, commandLineArgs.verbose, TcpSocketPort.ConnectionMode.keepAlive)
                    : new LocalSerialPortBuffered(commandLineArgs.device, commandLineArgs.baud,
                            timeout, commandLineArgs.verbose);
        } catch (NoSuchPortException | PortInUseException | UnsupportedCommOperationException ex) {
           throw new HarcHardwareException(ex);
        }
    }

    private static void usage(int exitcode) {
        StringBuilder str = new StringBuilder(256);
        argumentParser.usage(str);
        (exitcode == IrpUtils.EXIT_SUCCESS ? stdout : stderr).print(str);
        System.exit(exitcode);
    }

    private static int numberNonZeros(Object obj) {
        return obj == null ? 0 : 1;
    }

    private static int numberNonZeros(Object... obj) {
        int result = 0;
        for (Object o : obj)
            result += numberNonZeros(o);
        return result;
    }

    // https://specifications.freedesktop.org/basedir-spec/latest/index.html
    public static String defaultHistoryFile(String appName) {
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        String dataHome = (xdgDataHome == null || xdgDataHome.isEmpty())
                ? (System.getenv("HOME") + File.separator + ".local" + File.separator + "share")
                : xdgDataHome;
        String parentDir = dataHome + File.separator + defaultAppName;
        return parentDir + File.separator + appName + ".rl";
    }

    public static void main(String[] args) {
        setStdout(System.out);
        setStderr(System.err);
        argumentParser = new JCommander(commandLineArgs);
        argumentParser.setProgramName("ReadlineCommander");

        try {
            argumentParser.parse(args);
        } catch (ParameterException ex) {
            stderr.println(ex.getMessage());
            usage(IrpUtils.EXIT_USAGE_ERROR);
        }

        if (commandLineArgs.helpRequested)
            usage(IrpUtils.EXIT_SUCCESS);

        if (commandLineArgs.versionRequested) {
            stdout.println(versionString);
            stdout.println(Version.versionString);
            stdout.println("JVM: " + System.getProperty("java.vendor") + " " + System.getProperty("java.version") + " " + System.getProperty("os.name") + "-" + System.getProperty("os.arch"));
            stdout.println();
            stdout.println(Version.licenseString);
            System.exit(IrpUtils.EXIT_SUCCESS);
        }

        if (numberNonZeros(commandLineArgs.ip, commandLineArgs.device) != 1) {
            stderr.println("Exactly one of the options --ip and --device must be given");
            System.exit(IrpUtils.EXIT_USAGE_ERROR);
        }

        verbose = commandLineArgs.verbose;
        goodbyeWord =commandLineArgs.goodbyeWord;
        escape = commandLineArgs.escape;
        comment = commandLineArgs.comment;

        String historyFile = commandLineArgs.historyFile == null
                ? defaultHistoryFile(commandLineArgs.appname)
                : commandLineArgs.historyFile;

        if (commandLineArgs.arguments.isEmpty())
            try {
                init(commandLineArgs.configFile, historyFile, commandLineArgs.prompt, commandLineArgs.appname);
            } catch (IOException ex) {
                Logger.getLogger(ReadlineCommander.class.getName()).log(Level.SEVERE, null, ex);
                System.exit(IrpUtils.EXIT_IO_ERROR);
            }

        String frameString = commandLineArgs.carrageReturn ? "{0}\r"
                : commandLineArgs.newLine ? "{0}\n"
                : commandLineArgs.crlf ? "{0}\r\n"
                : "{0}";

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                try {
                    close();
                } catch (IOException ex) {
                    stderr.println(ex);
                }
            }
        });

        try (ICommandLineDevice commandLineDevice = createCommandLineDevice(commandLineArgs)) {
            commandLineDevice.open();
            FramedDevice stringCommander = new FramedDevice(commandLineDevice, frameString, commandLineArgs.uppercase);
            if (commandLineArgs.listen)
                // Interruprint with Ctrl-C does not quite work, does not close commandLineDevice.
                listen(stringCommander);
            else if (commandLineArgs.arguments.isEmpty()) {
                readEvalPrint(stringCommander, commandLineArgs.waitForAnswer, commandLineArgs.expectLines);
            } else {
                String command = String.join(" ", commandLineArgs.arguments);
                evalPrint(stringCommander, defaultWaitForAnswer, defaultPort, command);
            }
        } catch (UnknownHostException ex) {
            stderr.println("Unknown host \"" + commandLineArgs.ip + "\"");
        } catch (HarcHardwareException | IOException ex) {
            stderr.println(ex);
        } finally {
            if (commandLineArgs.arguments.isEmpty()) {
                try {
                    close();
                } catch (IOException ex) {
                    stderr.println(ex);
                }
            }
        }
    }

    private ReadlineCommander() {
    }

    private final static class CommandLineArgs {

        private final static int defaultTimeout = 2000;

        @Parameter(names = {"-a", "--appname"}, description = "Appname for Readline")
        private String appname = defaultAppName;

        @Parameter(names = {"-B", "--bye"}, description = "If the string given as argument is received, close the connection")
        private String goodbyeWord = null;

        @Parameter(names = {"-b", "--baud"}, description = "Baudrate for serial devices")
        private int baud = defaultBaudrate;

        @Parameter(names = {"-c", "--config"}, description = "Readline configuration file")
        private String configFile = null;

        @Parameter(names = {"--comment"}, description = "Define a comment character sequence")
        private String comment = null;

        @Parameter(names = {"-d", "--device"}, description = "Device name, e.g. COM7: or /dev/ttyS0")
        private String device = null;

        @Parameter(names = {"--escape"}, description = "Define an escape character sequence.")
        private String escape = null;

        @Parameter(names = {"-#", "--expect-lines"},
                description = "If >= 0, number of return lines to expect. If < 0, takes as many lines that are available within waitForAnswer.")
        private int expectLines = 1;

        @Parameter(names = {"-h", "--help", "-?"}, description = "Display help message")
        private boolean helpRequested = false;

        @Parameter(names = {"-H", "--history"}, description = "History file name")
        private String historyFile = defaultHistoryFileName;

        @Parameter(names = {"-i", "--ip"}, description = "IP address or name")
        private String ip = null;

        @Parameter(names = {"-l", "--listen"}, description = "Listen forever, just echo to stdout. Disables timeout. Press ctrl-C to stop.")
        private boolean listen = false;

        @Parameter(names = {"-p", "--port"}, description = "Port number")
        private int port = defaultPort;

        @Parameter(names = {"-P", "--prompt"}, description = "Prompt")
        private String prompt = defaultPrompt;

        @Parameter(names = {"-n", "--nl", "--newline"}, description = "End the lines with newline (\\n, 0x0A)")
        private boolean newLine = false;

        @Parameter(names = {"-r", "--cr", "--return"}, description = "End the lines with carrage return (\\r, 0x0D)")
        private boolean carrageReturn = false;

        @Parameter(names = {"--crlf"}, description = "End the lines with carrage return and linefeed (\\r\\n, 0x0D0x0A)")
        private boolean crlf = false;

        @Parameter(names = {"-t", "--timeout"}, description = "Timeout in milliseconds")
        private int timeout = defaultTimeout;

        @Parameter(names = {"-u", "--uppercase"}, description = "Turn inputs to UPPERCASE")
        private boolean uppercase = false;

        @Parameter(names = {"-V", "--version"}, description = "Display version information")
        private boolean versionRequested;

        @Parameter(names = {"-v", "--verbose"}, description = "Execute commands verbosely")
        private boolean verbose;

        @Parameter(names = {"-w", "--wait"}, description = "Microseconds to wait for answer")
        private int waitForAnswer = defaultWaitForAnswer;

        @Parameter(description = "[arguments to be sent]")
        private ArrayList<String> arguments = new ArrayList<>(8);
    }
}
