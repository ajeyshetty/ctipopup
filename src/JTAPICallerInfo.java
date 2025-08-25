import javax.telephony.*;
import javax.telephony.events.*;
import javax.telephony.callcontrol.*;
import javax.telephony.callcontrol.events.*;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.awt.Desktop;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class JTAPICallerInfo implements CallObserver {
    private static final Logger LOGGER = Logger.getLogger(JTAPICallerInfo.class.getName());
    private final String urlTemplate;
    // trigger values: CREATED, ALERTING, CONNECTED, RINGING
    private final String trigger;
    private final String monitoredAddress; // optional address name this listener is primarily for
    // store calling number observed on ConnCreatedEv keyed by Call
    private final Map<Call, String> callCaller = Collections.synchronizedMap(new WeakHashMap<Call, String>());
    // track Calls for which we've already opened the URL (so we open only once)
    private final java.util.Set<Call> urlOpened = Collections.synchronizedSet(java.util.Collections.newSetFromMap(new WeakHashMap<Call, Boolean>()));

    public JTAPICallerInfo(String urlTemplate) {
        this(urlTemplate, "CONNECTED");
    }

    public JTAPICallerInfo(String urlTemplate, String trigger) {
        this(urlTemplate, trigger, null);
    }

    public JTAPICallerInfo(String urlTemplate, String trigger, String monitoredAddress) {
        this.urlTemplate = urlTemplate;
        this.trigger = trigger == null ? "CONNECTED" : trigger.toUpperCase();
        this.monitoredAddress = monitoredAddress;
    }

    public JTAPICallerInfo() {
        this(null, "CONNECTED");
    }
    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: java JTAPICallerInfo <providerString> <addressName> <durationSeconds>");
            System.out.println("Example providerString: cucm1;login=watson;passwd=secret");
            return;
        }

        String providerString = args[0];
        String addressName = args[1];
        int durationSeconds = Integer.parseInt(args[2]);
        String urlTemplate = null;
        String trigger = null;
        if (args.length > 3) {
            urlTemplate = args[3];
        }
        if (args.length > 4) {
            trigger = args[4];
        }

        try {
            JtapiPeer peer = JtapiPeerFactory.getJtapiPeer(null);
            // Obtain provider using the provider string (CUCM details go here).
            // Example providerString: "cucm1;login=watson;passwd=secret"
            Provider provider = peer.getProvider(providerString);

            try {
                JTAPICallerInfo listener = new JTAPICallerInfo(urlTemplate, trigger, addressName.equalsIgnoreCase("ALL") ? null : addressName);
                if ("ALL".equalsIgnoreCase(addressName)) {
                    // subscribe to all addresses the provider exposes
                    Address[] all = provider.getAddresses();
                    for (Address a : all) {
                        a.addCallObserver(listener);
                    }
                    System.out.println("Listening for calls on ALL addresses (" + all.length + ")");
                } else {
                    Address address = provider.getAddress(addressName);
                    address.addCallObserver(listener);
                    System.out.println("Listening for calls on address: " + addressName);
                }
            } catch (Exception ex) {
                // If address not in domain, try a fuzzy search for names that contain the token
                System.out.println("Failed to subscribe to address '" + addressName + "': " + ex.getMessage());
                try {
                    Address[] available = provider.getAddresses();
                    System.out.println("Provider exposes " + available.length + " addresses. Searching for matches to '" + addressName + "'...");
                    java.util.List<Address> matches = new java.util.ArrayList<>();
                    String token = addressName.toLowerCase();
                    for (Address a : available) {
                        try {
                            if (a.getName().toLowerCase().contains(token)) {
                                matches.add(a);
                            }
                        } catch (Exception inner) {
                            // ignore per-address read errors
                        }
                    }
                    if (!matches.isEmpty()) {
                        System.out.println("Found " + matches.size() + " matching addresses; subscribing to them:");
                        for (Address m : matches) {
                            System.out.println(" - " + m.getName());
                                try {
                                m.addCallObserver(new JTAPICallerInfo(urlTemplate, trigger, m.getName()));
                            } catch (Exception subEx) {
                                System.out.println("Failed to subscribe to " + m.getName() + ": " + subEx.getMessage());
                            }
                        }
                    } else {
                        System.out.println("No matching addresses found containing '" + addressName + "'. Sample addresses:");
                        for (int i = 0; i < Math.min(20, available.length); i++) {
                            System.out.println(" - " + available[i].getName());
                        }
                        throw ex;
                    }
                } catch (Exception e2) {
                    System.out.println("Also failed to list/provider-search addresses: " + e2.getMessage());
                    throw ex;
                }
            }

            Thread.sleep(durationSeconds * 1000L);
            provider.shutdown();
            System.out.println("Exiting");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void callChangedEvent(CallEv[] events) {
        for (CallEv ev : events) {
            // Track ConnCreatedEv to capture the initial calling number
            if (ev instanceof ConnCreatedEv) {
                Connection conn = ((ConnCreatedEv) ev).getConnection();
                try {
                    Address fromAddr = conn.getAddress();
                    String callingNumber = fromAddr != null ? fromAddr.getName() : null;
                    if (callingNumber != null) {
                        Call call = conn.getCall();
                        callCaller.put(call, callingNumber);
                        LOGGER.fine("ConnCreatedEv observed caller=" + callingNumber + " call=" + call);
                    }
                } catch (Exception e) {
                    String err = "Failed to handle ConnCreatedEv: " + e.getMessage();
                    System.out.println(err);
                    writeLog(err);
                }
            }
            // Open URL on RINGING or CONNECTED depending on trigger.
            // If a monitoredAddress is specified, prefer Terminal-connection events (TermConnRingingEv / TermConnActiveEv)
            // so we act only when the monitored terminal actually rings/answers. Otherwise fall back to Connection events.
            if ("RINGING".equals(this.trigger)) {
                if (this.monitoredAddress != null && ev instanceof TermConnRingingEv) {
                    try {
                        TerminalConnection tc = ((TermConnRingingEv) ev).getTerminalConnection();
                        Terminal t = tc.getTerminal();
                        String termName = t != null ? t.getName() : null;
                        String msg = "TermConnRingingEv terminal=" + termName + " tc=" + tc;
                        System.out.println(msg);
                        writeLog(msg);
                        Connection innerConn = tc.getConnection();
                        Address innerAddr = innerConn != null ? innerConn.getAddress() : null;
                        String connName = innerAddr != null ? innerAddr.getName() : null;
                        boolean addressMatches = false;
                        try {
                            addressMatches = (termName != null && this.monitoredAddress != null && termName.equalsIgnoreCase(this.monitoredAddress))
                                || (connName != null && this.monitoredAddress != null && connName.equalsIgnoreCase(this.monitoredAddress))
                                || (connName != null && this.monitoredAddress != null && connName.toLowerCase().contains(this.monitoredAddress.toLowerCase()));
                        } catch (Exception _ignore) { addressMatches = false; }
                        if (addressMatches) {
                            Call call = innerConn != null ? innerConn.getCall() : null;
                            String callingNumber = call != null ? callCaller.get(call) : null;
                            if (callingNumber == null) {
                                Address a = innerConn != null ? innerConn.getAddress() : null;
                                callingNumber = a != null ? a.getName() : null;
                            }
                            if (callingNumber != null && this.urlTemplate != null && !this.urlTemplate.isEmpty() && !urlOpened.contains(call)) {
                                openUrlWithNumber(this.urlTemplate, callingNumber);
                                urlOpened.add(call);
                            }
                        }
                    } catch (Exception e) {
                        String err = "Failed to handle TermConnRingingEv: " + e.getMessage();
                        System.out.println(err);
                        writeLog(err);
                    }
                } else if (ev instanceof ConnAlertingEv) {
                    Connection conn = ((ConnAlertingEv) ev).getConnection();
                    try {
                        Call call = conn.getCall();
                        String callingNumber = callCaller.get(call);
                        if (callingNumber == null) {
                            Address fromAddr = conn.getAddress();
                            callingNumber = fromAddr != null ? fromAddr.getName() : null;
                        }
                        String msg = "ConnAlertingEv - callingNumber=" + callingNumber + " conn=" + conn;
                        System.out.println(msg);
                        writeLog(msg);
                        if (callingNumber != null && this.urlTemplate != null && !this.urlTemplate.isEmpty()) {
                            try {
                                if (this.monitoredAddress != null) {
                                    Address connAddr = conn.getAddress();
                                    String connName = connAddr != null ? connAddr.getName() : null;
                                    if (connName == null || !connName.equalsIgnoreCase(this.monitoredAddress)) {
                                        writeLog("Skipping alerting open: connection address=" + connName + " monitored=" + this.monitoredAddress);
                                        continue;
                                    }
                                }
                            } catch (Exception _ignore) {}
                            if (!urlOpened.contains(call)) {
                                openUrlWithNumber(this.urlTemplate, callingNumber);
                                urlOpened.add(call);
                            }
                        }
                    } catch (Exception e) {
                        String err = "Failed to handle ConnAlertingEv: " + e.getMessage();
                        System.out.println(err);
                        writeLog(err);
                    }
                }
            }

            if ("CONNECTED".equals(this.trigger)) {
                if (this.monitoredAddress != null && ev instanceof TermConnActiveEv) {
                    try {
                        TerminalConnection tc = ((TermConnActiveEv) ev).getTerminalConnection();
                        Terminal t = tc.getTerminal();
                        String termName = t != null ? t.getName() : null;
                        String msg = "TermConnActiveEv terminal=" + termName + " tc=" + tc;
                        System.out.println(msg);
                        writeLog(msg);
                        Connection innerConn = tc.getConnection();
                        Address innerAddr = innerConn != null ? innerConn.getAddress() : null;
                        String connName = innerAddr != null ? innerAddr.getName() : null;
                        boolean addressMatches = false;
                        try {
                            addressMatches = (termName != null && this.monitoredAddress != null && termName.equalsIgnoreCase(this.monitoredAddress))
                                || (connName != null && this.monitoredAddress != null && connName.equalsIgnoreCase(this.monitoredAddress))
                                || (connName != null && this.monitoredAddress != null && connName.toLowerCase().contains(this.monitoredAddress.toLowerCase()));
                        } catch (Exception _ignore) { addressMatches = false; }
                        if (addressMatches) {
                            Call call = innerConn != null ? innerConn.getCall() : null;
                            String callingNumber = call != null ? callCaller.get(call) : null;
                            if (callingNumber == null) {
                                Address a = innerConn != null ? innerConn.getAddress() : null;
                                callingNumber = a != null ? a.getName() : null;
                            }
                            if (callingNumber != null && this.urlTemplate != null && !this.urlTemplate.isEmpty()) {
                                if (!urlOpened.contains(call)) {
                                    openUrlWithNumber(this.urlTemplate, callingNumber);
                                    urlOpened.add(call);
                                } else {
                                    System.out.println("URL already opened for call: " + call);
                                }
                            }
                        }
                    } catch (Exception e) {
                        String err = "Failed to handle TermConnActiveEv: " + e.getMessage();
                        System.out.println(err);
                        writeLog(err);
                    }
                } else if (ev instanceof ConnConnectedEv) {
                    Connection conn = ((ConnConnectedEv) ev).getConnection();
                    try {
                        Call call = conn.getCall();
                        String callingNumber = callCaller.get(call);
                        if (callingNumber == null) {
                            // fallback to connection address
                            Address fromAddr = conn.getAddress();
                            callingNumber = fromAddr != null ? fromAddr.getName() : null;
                        }
                        String msg = "ConnConnectedEv - callingNumber=" + callingNumber + " conn=" + conn;
                        System.out.println(msg);
                        writeLog(msg);
                        // open URL only once per Call
                        if (callingNumber != null && this.urlTemplate != null && !this.urlTemplate.isEmpty()) {
                            try {
                                if (this.monitoredAddress != null) {
                                    Address connAddr = conn.getAddress();
                                    String connName = connAddr != null ? connAddr.getName() : null;
                                    if (connName == null || !connName.equalsIgnoreCase(this.monitoredAddress)) {
                                        writeLog("Skipping connected open: connection address=" + connName + " monitored=" + this.monitoredAddress);
                                        continue;
                                    }
                                }
                            } catch (Exception _ignore) {}
                            if (!urlOpened.contains(call)) {
                                openUrlWithNumber(this.urlTemplate, callingNumber);
                                urlOpened.add(call);
                            } else {
                                System.out.println("URL already opened for call: " + call);
                            }
                        }
                    } catch (Exception e) {
                        String err = "Failed to handle ConnConnectedEv: " + e.getMessage();
                        System.out.println(err);
                        writeLog(err);
                    }
                }
            } else if (ev instanceof CallObservationEndedEv) {
                try {
                    Call call = ((CallObservationEndedEv) ev).getCall();
                    callCaller.remove(call);
                    urlOpened.remove(call);
                } catch (Exception ignore) {}
            } else {
                // Log other events at DEBUG level
                String other = "Event: " + ev;
                System.out.println(other);
                writeLog(other);
            }
        }
    }

    private void openUrlWithNumber(String template, String number) {
        try {
            String encoded = URLEncoder.encode(number, StandardCharsets.UTF_8.toString());
            String url = template.replace("{number}", encoded).replace("%s", encoded);
            // If template does not contain a placeholder, append the number
            if (!template.contains("{number}") && !template.contains("%s")) {
                if (!url.endsWith("/") && !url.contains("?")) url = url + "/" + encoded;
                else url = url + encoded;
            }
            // Try to open via Desktop.browse first
            writeLog("Opening URL: " + url);
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(new URI(url));
                    LOGGER.info("Opened URL via Desktop: " + url);
                    return;
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Desktop browse failed: " + e.getMessage(), e);
                writeLog("Desktop browse failed: " + e.getMessage());
            }

            // Fallback: use PowerShell Start-Process to open the URL on Windows
            try {
                String safeUrl = url.replace("'", "''");
                String psCmd = "Start-Process -FilePath '" + safeUrl + "'";
                ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", psCmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    StringBuilder out = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        out.append(line).append('\n');
                    }
                    if (out.length() > 0) writeLog("PowerShell output: " + out.toString().trim());
                }
                int rc = p.waitFor();
                if (rc == 0) {
                    LOGGER.info("Opened URL via PowerShell: " + url);
                } else {
                    LOGGER.warning("PowerShell Start-Process returned exit code " + rc + " for URL: " + url);
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "PowerShell fallback failed: " + e.getMessage(), e);
                writeLog("PowerShell fallback failed: " + e.getMessage());
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to open URL: " + e.getMessage(), e);
        }
    }

    private void writeLog(String line) {
        File logFile = new File(System.getProperty("user.dir"), "call-events.log");
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        try (FileWriter fw = new FileWriter(logFile, true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter out = new PrintWriter(bw)) {
            out.println(ts + " " + line);
        } catch (IOException e) {
            // If logging fails, print to stderr
            System.err.println("Failed to write log: " + e.getMessage());
        }
    }
    // GUI popup removed for production; use system notifications or external caller if needed.
}
