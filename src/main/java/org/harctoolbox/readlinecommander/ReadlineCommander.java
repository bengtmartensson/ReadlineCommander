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
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import org.gnu.readline.Readline;
import org.gnu.readline.ReadlineLibrary;
import org.harctoolbox.IrpMaster.IrpUtils;
import org.harctoolbox.harchardware.FramedDevice;
import org.harctoolbox.harchardware.ICommandLineDevice;
import org.harctoolbox.harchardware.Version;
import org.harctoolbox.harchardware.comm.TcpSocketPort;

/**
 * Wrapper around <a href="https://github.com/bengtmartensson/java-readline.git">java-readline</a>.
 * It consists of static members only, since that goes for java-readline too.
 */
public class ReadlineCommander {

    private static final int telnetPort = 23;
    private static final int defaultWaitForAnswer = 1000; // milliseconds
    private static final String defaultHistoryFileName = ".rlchistory";
    private static final String defaultAppName = "ReadlineCommander";
    private static final String defaultPrompt = "RLC> ";
    private static final String versionString = "ReadlineCommander 0.1.0";

    private static String historyFile;
    private static String prompt;
    private static boolean initialized = false;
    private static boolean verbose = false;

    private ReadlineCommander() {
    }

    /**
     * Version of init with default (dumb) defaults.
     */
    public static void init() {
        init(null, defaultHistoryFileName, defaultPrompt, defaultAppName);
    }

    /**
     * Initializes the readline.
     *
     * @param confFile File name of the configuration file.
     * @param historyFile_ File name of the history file.
     * @param prompt_ Prompt for Readline to use.
     * @param appName appName for readline to use when interpreting its configuration. Must be != null.
     */
    public static void init(String confFile, String historyFile_, String prompt_, String appName) {
        historyFile = historyFile_;
        prompt = prompt_;

        try {
            Readline.load(ReadlineLibrary.GnuReadline);
            Readline.initReadline(appName);
            if (confFile != null) {
                if (new File(confFile).exists()) {
                    try {
                        Readline.readInitFile(confFile);
                    } catch (IOException ex) {
                        System.err.println(ex.getMessage());
                    }
                } else {
                    System.err.println("Warning: Cannot open readline configuration " + confFile + ", ignoring");
                }
            }

            if (historyFile != null) {
                if (new File(historyFile).exists()) {
                    try {
                        Readline.readHistoryFile(historyFile);
                    } catch (EOFException | UnsupportedEncodingException ex) {
                        System.err.println("This cannot happen.");
                    }
                } else {
                    System.err.println("Cannot open readline history " + historyFile
                            + ", will try to write it anyhow.");
                }
            }
        } catch (UnsatisfiedLinkError ignoreMe) {
            System.err.println("Could not load readline lib. Using simple stdin.");
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
        initialized = false;
        if (historyFile != null)
            Readline.writeHistoryFile(historyFile);
        Readline.cleanup();
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
        while (true) {
            String line = null;
            try {
                line = readline();
            } catch (IOException ex) {
                System.err.println(ex.getMessage());
            }

            if (line == null) { // EOF
                System.out.println();
                break;
            }
            String[] result = null;
            try {
                result = stringCommander.sendString(line, returnlines <= 0 ? -1 : returnlines, waitForAnswer);
                for (String str : result)
                    if (str != null) {
                        System.out.println(str);
                    }
                if (result != null && result.length > 0 && result[result.length-1].equals("Bye!"))
                    break;
            } catch (IOException ex) {
                System.err.println(ex.getMessage());
            }
        }
        if (verbose)
            System.out.println("Readline.readEvalPrint exited"); // ???
    }

    public static void readEvalPrint(ICommandLineDevice hardware, int waitForAnswer, int returnLines) {
        readEvalPrint(new FramedDevice(hardware), waitForAnswer, returnLines);
    }    private static void usage(int exitcode) {
        StringBuilder str = new StringBuilder();
        argumentParser.usage(str);

        str.append("\n"
                + "parameters: <protocol> <deviceno> [<subdevice_no>] commandno [<toggle>]\n"
                + "   or       <Pronto code>");

        (exitcode == IrpUtils.exitSuccess ? System.out : System.err).println(str);
        doExit(exitcode);
    }

    private static void doExit(int exitcode) {
        System.exit(exitcode);
    }

    private static void doExit(String message, int exitcode) {
        System.err.println(message);
        doExit(exitcode);
    }

    private final static class CommandLineArgs {

        private final static int defaultTimeout = 2000;

        @Parameter(names = {"-a", "--appname"}, description = "Appname for Readline")
        private String appname = defaultAppName;

        @Parameter(names = {"-c", "--config"}, description = "Readline configuration file")
        private String configFile = null;

        //@Parameter(names = {"-D", "--debug"}, description = "Debug code")
        //private int debug = 0;

        @Parameter(names = {"-d", "--device"}, description = "Device name, e.g. COM7: or /dev/ttyS0")
        private String device = null;

        @Parameter(names = {"-#", "--expect-lines"},
                description = "If >= 0, number of return lines to expect. If < 0, takes as many lines that are available within waitForAnswer.")
        private int expectLines = 1;

        @Parameter(names = {"-h", "--help", "-?"}, description = "Display help message")
        private boolean helpRequested = false;

        @Parameter(names = {"-H", "--history"}, description = "History file name")
        private String historyFile = defaultHistoryFileName;

        @Parameter(names = {"-i", "--ip"}, description = "IP address or name")
        private String ip = null;

        @Parameter(names = {"-p", "--port"}, description = "Port number")
        private int port = telnetPort;

        @Parameter(names = {"-P", "--prompt"}, description = "Prompt")
        private String prompt = defaultPrompt;

        @Parameter(names = {"-r", "--return"}, description = "End the lines with carrage return (\\r)")
        private boolean carrageReturn = false;

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

        @Parameter(description = "[parameters]")
        private ArrayList<String> parameters = new ArrayList<>();
    }

    private static JCommander argumentParser;
    private static CommandLineArgs commandLineArgs = new CommandLineArgs();

    private static Thread closeOnShutdown = new Thread() {
        @Override
        public void run() {
            try {
                System.err.println("Running shutdown hook");
                close();
            } catch (IOException e) {
                System.err.println(e.getMessage());
            }
        }
    };

    public static void main(String[] args) {
        argumentParser = new JCommander(commandLineArgs);
        argumentParser.setProgramName("ReadlineCommander");

        try {
            argumentParser.parse(args);
        } catch (ParameterException ex) {
            System.err.println(ex.getMessage());
            usage(IrpUtils.exitUsageError);
        }

        if (commandLineArgs.helpRequested)
            usage(IrpUtils.exitSuccess);

        if (commandLineArgs.versionRequested) {
            System.out.println(versionString);
            System.out.println(Version.versionString);
            System.out.println("JVM: " + System.getProperty("java.vendor") + " " + System.getProperty("java.version") + " " + System.getProperty("os.name") + "-" + System.getProperty("os.arch"));
            System.out.println();
            System.out.println(Version.licenseString);
            doExit(IrpUtils.exitSuccess);
        }

        if ((commandLineArgs.ip != null ? 1 : 0) + (commandLineArgs.device !=null ? 1 : 0) != 1)
            doExit("Exactly one of the options --ip and --device must be given", IrpUtils.exitUsageError);

        Runtime.getRuntime().addShutdownHook(closeOnShutdown);
        verbose = commandLineArgs.verbose;

        init(commandLineArgs.configFile, commandLineArgs.historyFile, commandLineArgs.prompt, commandLineArgs.appname);
        try (ICommandLineDevice commandLineDevice = new TcpSocketPort(commandLineArgs.ip, commandLineArgs.port,
                commandLineArgs.timeout, commandLineArgs.verbose, TcpSocketPort.ConnectionMode.keepAlive)) {
            String frameString = commandLineArgs.carrageReturn ? "{0}\r" : "{0}";
            FramedDevice stringCommander = new FramedDevice(commandLineDevice, frameString, commandLineArgs.uppercase);
            readEvalPrint(stringCommander, commandLineArgs.waitForAnswer, commandLineArgs.expectLines);
        } catch (UnknownHostException ex) {
            System.err.println("Unknown host \"" + commandLineArgs.ip + "\"");
        } catch (IOException ex) {
            System.err.println(ex.getMessage());
        } finally {
            try {
                close();
            } catch (IOException ex) {
                System.err.println(ex.getMessage());
            }
        }
    }
}
