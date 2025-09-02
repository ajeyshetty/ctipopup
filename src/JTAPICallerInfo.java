import javax.telephony.*;
import javax.telephony.events.*;
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
    // Diagnostic info to help debug trigger behavior
    String info = "JTAPICallerInfo init: trigger=" + this.trigger + " monitoredAddress=" + (this.monitoredAddress == null ? "<none>" : this.monitoredAddress) + " urlTemplate=" + (this.urlTemplate == null ? "<none>" : this.urlTemplate);
    System.out.println(info);
    writeLog(info);
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
            try {
                // Handle CallCtlTermConnRingingEv - important for knowing when terminal is ready for operations
                if (ev instanceof CallCtlTermConnRingingEv) {
                    try {
                        CallCtlTermConnRingingEv ringingEv = (CallCtlTermConnRingingEv) ev;
                        TerminalConnection tc = ringingEv.getTerminalConnection();
                        Terminal t = tc != null ? tc.getTerminal() : null;
                        String termName = t != null ? t.getName() : null;
                        String msg = "CallCtlTermConnRingingEv terminal=" + termName + " tc=" + tc;
                        System.out.println(msg);
                        writeLog(msg);

                        // Update call registry with ringing state
                        Connection innerConn = tc != null ? tc.getConnection() : null;
                        Call call = innerConn != null ? innerConn.getCall() : null;
                        if (call != null) {
                            // Get calling number to check if this is an outbound call
                            String callingNumber = callCaller.get(call);
                            if (callingNumber == null) {
                                Address a = innerConn != null ? innerConn.getAddress() : null;
                                callingNumber = a != null ? a.getName() : null;
                            }
                            
                            // For outbound calls, only update to RINGING if the ringing terminal is not the monitored terminal
                            // (outbound calls don't ring at the originating terminal)
                            boolean shouldUpdateRinging = true;
                            if (isOutboundCall(call, callingNumber) && termName != null && 
                                this.monitoredAddress != null && termName.equalsIgnoreCase(this.monitoredAddress)) {
                                shouldUpdateRinging = false;
                                writeLog("CallCtlTermConnRingingEv - outbound call ringing at originating terminal, not updating state");
                            }
                            
                            if (shouldUpdateRinging) {
                                try {
                                    CallRegistry.getInstance().addOrUpdate(call, null, "RINGING", termName);
                                } catch (Throwable _ignore) {}
                            }
                        }
                    } catch (Exception e) {
                        String err = "Failed to handle CallCtlTermConnRingingEv: " + e.getMessage();
                        System.out.println(err);
                        writeLog(err);
                    }
                }

                // Track ConnCreatedEv to capture the initial calling number
                if (ev instanceof ConnCreatedEv) {
                    try {
                        Connection conn = ((ConnCreatedEv) ev).getConnection();
                        Address fromAddr = conn != null ? conn.getAddress() : null;
                        String callingNumber = fromAddr != null ? fromAddr.getName() : null;
                        if (callingNumber != null) {
                            Call call = conn.getCall();
                            callCaller.put(call, callingNumber);
                            LOGGER.fine("ConnCreatedEv observed caller=" + callingNumber + " call=" + call);
                            
                            // Only set CREATED if this is a new call or if it's not already ALERTING (for outbound calls)
                            CallRegistry.CallInfo existingInfo = CallRegistry.getInstance().snapshot().stream()
                                .filter(info -> info.call.equals(call))
                                .findFirst().orElse(null);
                            
                            if (existingInfo == null || !"ALERTING".equals(existingInfo.state)) {
                                try { CallRegistry.getInstance().addOrUpdate(call, callingNumber, "CREATED", this.monitoredAddress); } catch (Throwable _ignore) {}
                            } else {
                                writeLog("ConnCreatedEv: Call already in ALERTING state, not overriding");
                            }
                        }
                    } catch (Exception e) {
                        String err = "Failed to handle ConnCreatedEv: " + e.getMessage();
                        System.out.println(err);
                        writeLog(err);
                    }
                }

                // RINGING trigger: prefer terminal events for a monitored address, otherwise use connection alerting
                if ("RINGING".equals(this.trigger)) {
                    if (this.monitoredAddress != null && ev instanceof TermConnRingingEv) {
                        try {
                            TerminalConnection tc = ((TermConnRingingEv) ev).getTerminalConnection();
                            Terminal t = tc != null ? tc.getTerminal() : null;
                            String termName = t != null ? t.getName() : null;
                            String msg = "TermConnRingingEv terminal=" + termName + " tc=" + tc;
                            System.out.println(msg);
                            writeLog(msg);
                            Connection innerConn = tc != null ? tc.getConnection() : null;
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
                                    if (callingNumber != null && call != null) {
                                        callCaller.put(call, callingNumber);
                                        try { CallRegistry.getInstance().addOrUpdate(call, callingNumber, "CREATED", this.monitoredAddress); } catch (Throwable _ignore) {}
                                        LOGGER.fine("TermConnRingingEv observed caller=" + callingNumber + " call=" + call);
                                    }
                                }
                                if (callingNumber != null && this.urlTemplate != null && !this.urlTemplate.isEmpty() && !urlOpened.contains(call)) {
                                    // Skip screen pop for outbound calls
                                    if (!isOutboundCall(call, callingNumber)) {
                                        openUrlWithNumber(this.urlTemplate, callingNumber);
                                        urlOpened.add(call);
                                        try { CallRegistry.getInstance().addOrUpdate(call, callingNumber, "ALERTING", this.monitoredAddress); } catch (Throwable _ignore) {}
                                    } else {
                                        writeLog("Skipping screen pop for outbound call: " + callingNumber);
                                        urlOpened.add(call); // Mark as opened to prevent future attempts
                                    }
                                }
                            }
                        } catch (Exception e) {
                            String err = "Failed to handle TermConnRingingEv: " + e.getMessage();
                            System.out.println(err);
                            writeLog(err);
                        }
                    } else if (ev instanceof ConnAlertingEv) {
                        try {
                            Connection conn = ((ConnAlertingEv) ev).getConnection();
                            Call call = conn != null ? conn.getCall() : null;
                            String callingNumber = call != null ? callCaller.get(call) : null;
                            if (callingNumber == null) {
                                Address fromAddr = conn != null ? conn.getAddress() : null;
                                callingNumber = fromAddr != null ? fromAddr.getName() : null;
                            }
                            String msg = "ConnAlertingEv - callingNumber=" + callingNumber + " conn=" + conn;
                            System.out.println(msg);
                            writeLog(msg);
                            
                            // Always update call registry with ALERTING state, regardless of URL template
                            try { 
                                CallRegistry.getInstance().addOrUpdate(call, callingNumber, "ALERTING", this.monitoredAddress); 
                            } catch (Throwable _ignore) {}
                            
                            // Only process screen pop if we have a valid URL template
                            if (callingNumber != null && this.urlTemplate != null && !this.urlTemplate.isEmpty()) {
                                try {
                                    if (this.monitoredAddress != null) {
                                        Address connAddr = conn != null ? conn.getAddress() : null;
                                        String connName = connAddr != null ? connAddr.getName() : null;
                                        if (connName == null || !connName.equalsIgnoreCase(this.monitoredAddress)) {
                                                writeLog("Skipping alerting open: connection address=" + connName + " monitored=" + this.monitoredAddress);
                                                // monitored address doesn't match — skip this event
                                                return;
                                            }
                                    }
                                } catch (Exception _ignore) {}
                                if (!urlOpened.contains(call)) {
                                    // Skip screen pop for outbound calls
                                    if (!isOutboundCall(call, callingNumber)) {
                                        openUrlWithNumber(this.urlTemplate, callingNumber);
                                        urlOpened.add(call);
                                    } else {
                                        writeLog("Skipping screen pop for outbound call: " + callingNumber);
                                        urlOpened.add(call); // Mark as opened to prevent future attempts
                                    }
                                } else {
                                    System.out.println("URL already opened for call: " + call);
                                }
                            }
                        } catch (Exception e) {
                            String err = "Failed to handle ConnAlertingEv: " + e.getMessage();
                            System.out.println(err);
                            writeLog(err);
                        }
                    }
                }

                // CONNECTED trigger: prefer terminal active events for monitored address, otherwise connection connected
                if ("CONNECTED".equals(this.trigger)) {
                    if (this.monitoredAddress != null && ev instanceof TermConnActiveEv) {
                        try {
                            TerminalConnection tc = ((TermConnActiveEv) ev).getTerminalConnection();
                            Terminal t = tc != null ? tc.getTerminal() : null;
                            String termName = t != null ? t.getName() : null;
                            String msg = "TermConnActiveEv terminal=" + termName + " tc=" + tc;
                            System.out.println(msg);
                            writeLog(msg);
                            Connection innerConn = tc != null ? tc.getConnection() : null;
                            Address innerAddr = innerConn != null ? innerConn.getAddress() : null;
                            String connName = innerAddr != null ? innerAddr.getName() : null;
                            boolean addressMatches = false;
                            try {
                                addressMatches = (termName != null && this.monitoredAddress != null && termName.equalsIgnoreCase(this.monitoredAddress))
                                        || (connName != null && this.monitoredAddress != null && connName.equalsIgnoreCase(this.monitoredAddress))
                                        || (connName != null && this.monitoredAddress != null && connName.toLowerCase().contains(this.monitoredAddress.toLowerCase()));
                            } catch (Exception _ignore) { addressMatches = false; }
                            writeLog("TermConnActiveEv - termName=" + termName + ", connName=" + connName + ", monitoredAddress=" + this.monitoredAddress + ", addressMatches=" + addressMatches);
                            if (addressMatches) {
                                Call call = innerConn != null ? innerConn.getCall() : null;
                                String callingNumber = call != null ? callCaller.get(call) : null;
                                if (callingNumber == null) {
                                    Address a = innerConn != null ? innerConn.getAddress() : null;
                                    callingNumber = a != null ? a.getName() : null;
                                }
                                
                                writeLog("TermConnActiveEv - call=" + call + ", callingNumber=" + callingNumber + ", conn=" + innerConn);
                                
                                // For outbound calls, don't set CONNECTED in TermConnActiveEv
                                // Let the destination connection set CONNECTED when established
                                boolean isOutbound = isOutboundCall(call, callingNumber);
                                writeLog("TermConnActiveEv - isOutboundCall result: " + isOutbound + " for callingNumber: " + callingNumber);
                                
                                // Also check if call has multiple connections - if not, likely outbound call not fully connected
                                Connection[] connections = call != null ? call.getConnections() : null;
                                int connectionCount = connections != null ? connections.length : 0;
                                writeLog("TermConnActiveEv - connection count: " + connectionCount);
                                
                                if (isOutbound || connectionCount <= 1) {
                                    writeLog("TermConnActiveEv - not setting CONNECTED (outbound or single connection)");
                                    // Don't update state for outbound calls or calls with single connection
                                } else {
                                    // Check if this call was manually picked up
                                    CallRegistry.CallInfo callInfo = CallRegistry.getInstance().snapshot().stream()
                                        .filter(info -> info.call.equals(call))
                                        .findFirst().orElse(null);
                                    boolean manuallyPickedUp = callInfo != null && callInfo.manuallyPickedUp;
                                    writeLog("TermConnActiveEv - manuallyPickedUp: " + manuallyPickedUp);
                                    
                                    if (manuallyPickedUp) {
                                        writeLog("TermConnActiveEv - inbound call manually picked up, setting CONNECTED");
                                        try { 
                                            CallRegistry.getInstance().addOrUpdate(call, callingNumber, "CONNECTED", this.monitoredAddress); 
                                        } catch (Throwable _ignore) {}
                                    } else {
                                        writeLog("TermConnActiveEv - inbound call, keeping ALERTING (requires manual pickup)");
                                        // For inbound calls, keep them in ALERTING state until manually picked up
                                        // The UI will set them to CONNECTED only when manually picked up via pickCall()
                                        try { 
                                            CallRegistry.getInstance().addOrUpdate(call, callingNumber, "ALERTING", this.monitoredAddress); 
                                        } catch (Throwable _ignore) {}
                                    }
                                }
                                
                                // Only process screen pop if we have a valid URL template
                                if (callingNumber != null && this.urlTemplate != null && !this.urlTemplate.isEmpty()) {
                                    if (!urlOpened.contains(call)) {
                                        // Skip screen pop for outbound calls
                                        if (!isOutboundCall(call, callingNumber)) {
                                            openUrlWithNumber(this.urlTemplate, callingNumber);
                                            urlOpened.add(call);
                                        } else {
                                            writeLog("Skipping screen pop for outbound call: " + callingNumber);
                                            urlOpened.add(call); // Mark as opened to prevent future attempts
                                        }
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
                        try {
                            Connection conn = ((ConnConnectedEv) ev).getConnection();
                            Call call = conn != null ? conn.getCall() : null;
                            String callingNumber = call != null ? callCaller.get(call) : null;
                            
                            // For outbound calls, if we don't have a calling number stored,
                            // check if this is an outbound call by comparing with monitored address
                            if (callingNumber == null) {
                                Address fromAddr = conn != null ? conn.getAddress() : null;
                                String connAddrName = fromAddr != null ? fromAddr.getName() : null;
                                
                                // Check if this connection address matches our monitored address (outbound call)
                                if (connAddrName != null && this.monitoredAddress != null && 
                                    connAddrName.equalsIgnoreCase(this.monitoredAddress)) {
                                    // This is likely an outbound call - try to get the dialed number
                                    // For outbound calls, we need to look at the other connection in the call
                                    Connection[] connections = call != null ? call.getConnections() : null;
                                    if (connections != null) {
                                        for (Connection c : connections) {
                                            if (c != null && c != conn) {
                                                Address destAddr = c.getAddress();
                                                if (destAddr != null) {
                                                    callingNumber = destAddr.getName();
                                                    if (callingNumber != null) {
                                                        writeLog("Detected outbound call destination: " + callingNumber);
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    callingNumber = connAddrName;
                                }
                            }
                            
                            String msg = "ConnConnectedEv - callingNumber=" + callingNumber + " conn=" + conn;
                            System.out.println(msg);
                            writeLog(msg);
                            
                            // Capture the callingNumber value for use in Timer (must be final)
                            final String finalCallingNumber = callingNumber;
                            
                            // Determine the appropriate state based on call type and connection
                            String stateToSet = "ALERTING"; // Default to ALERTING for safety
                            
                            // For inbound calls, do NOT automatically set to CONNECTED
                            // They should remain in ALERTING/RINGING until manually picked up via UI
                            boolean isOutbound = isOutboundCall(call, finalCallingNumber);
                            writeLog("ConnConnectedEv - isOutboundCall result: " + isOutbound + " for callingNumber: " + finalCallingNumber + " monitoredAddress: " + this.monitoredAddress);
                            
                            // Check if this call was manually picked up
                            CallRegistry.CallInfo callInfo = CallRegistry.getInstance().snapshot().stream()
                                .filter(info -> info.call.equals(call))
                                .findFirst().orElse(null);
                            boolean manuallyPickedUp = callInfo != null && callInfo.manuallyPickedUp;
                            writeLog("ConnConnectedEv - manuallyPickedUp: " + manuallyPickedUp);
                            
                            if (!isOutbound && !manuallyPickedUp) {
                                // This is an inbound call that was NOT manually picked up - keep it in ALERTING state
                                // The UI will set it to CONNECTED only when manually picked up
                                stateToSet = "ALERTING";
                                writeLog("Inbound call ConnConnectedEv - keeping ALERTING state (requires manual pickup)");
                            } else if (manuallyPickedUp) {
                                // This call was manually picked up - allow CONNECTED state
                                stateToSet = "CONNECTED";
                                writeLog("ConnConnectedEv - allowing CONNECTED state (manually picked up)");
                            } else {
                                // For outbound calls, be more conservative about setting CONNECTED
                                Address connAddr = conn != null ? conn.getAddress() : null;
                                String connAddrName = connAddr != null ? connAddr.getName() : null;
                                
                                // If this connection address matches our monitored address, it's the originating side
                                if (connAddrName != null && this.monitoredAddress != null && 
                                    connAddrName.equalsIgnoreCase(this.monitoredAddress)) {
                                    // For originating connections, keep ALERTING until we're sure the call is established
                                    stateToSet = "ALERTING";
                                    writeLog("Outbound call originating connection - keeping ALERTING state");
                                } else if (finalCallingNumber != null && !finalCallingNumber.equals(this.monitoredAddress)) {
                                    // This is clearly the destination connection (different number than monitored address)
                                    // For outbound calls, add a delay to simulate ringing before setting CONNECTED
                                    writeLog("Outbound call destination connection - scheduling CONNECTED after delay");
                                    
                                    // Schedule the state update after a delay
                                    javax.swing.Timer timer = new javax.swing.Timer(2000, new java.awt.event.ActionListener() {
                                        public void actionPerformed(java.awt.event.ActionEvent e) {
                                            try {
                                                CallRegistry.getInstance().addOrUpdate(call, finalCallingNumber, "CONNECTED", JTAPICallerInfo.this.monitoredAddress);
                                                writeLog("Outbound call destination connection - delayed CONNECTED state set");
                                            } catch (Throwable ignore) {}
                                        }
                                    });
                                    timer.setRepeats(false);
                                    timer.start();
                                    
                                    // Keep ALERTING state for now
                                    stateToSet = "ALERTING";
                                    writeLog("Outbound call destination connection - keeping ALERTING during delay");
                                } else {
                                    // Uncertain case - keep current state or set ALERTING
                                    CallRegistry.CallInfo existingInfo = CallRegistry.getInstance().snapshot().stream()
                                        .filter(info -> info.call.equals(call))
                                        .findFirst().orElse(null);
                                    if (existingInfo != null && "ALERTING".equals(existingInfo.state)) {
                                        stateToSet = "ALERTING"; // Preserve ALERTING state
                                        writeLog("Outbound call uncertain connection - preserving ALERTING state");
                                    } else {
                                        stateToSet = "ALERTING"; // Changed from CONNECTED to ALERTING for safety
                                        writeLog("Outbound call uncertain connection - setting ALERTING state");
                                    }
                                }
                            }
                            
                            // Always update call registry with the determined state, regardless of URL template
                            try { 
                                CallRegistry.getInstance().addOrUpdate(call, finalCallingNumber, stateToSet, this.monitoredAddress); 
                            } catch (Throwable _ignore) {}
                            
                            // Only process screen pop if we have a valid URL template
                            if (callingNumber != null && this.urlTemplate != null && !this.urlTemplate.isEmpty()) {
                                try {
                                    if (this.monitoredAddress != null) {
                                        Address connAddr = conn != null ? conn.getAddress() : null;
                                        String connName = connAddr != null ? connAddr.getName() : null;
                                        if (connName == null || !connName.equalsIgnoreCase(this.monitoredAddress)) {
                                                writeLog("Skipping connected open: connection address=" + connName + " monitored=" + this.monitoredAddress);
                                                // monitored address doesn't match — skip this event
                                                return;
                                            }
                                    }
                                } catch (Exception _ignore) {}
                                if (!urlOpened.contains(call)) {
                                    // Skip screen pop for outbound calls
                                    if (!isOutboundCall(call, callingNumber)) {
                                        openUrlWithNumber(this.urlTemplate, callingNumber);
                                        urlOpened.add(call);
                                    } else {
                                        writeLog("Skipping screen pop for outbound call: " + callingNumber);
                                        urlOpened.add(call); // Mark as opened to prevent future attempts
                                    }
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
                }

                // Call ended -> cleanup
                if (ev instanceof CallObservationEndedEv) {
                    try {
                        Call call = ((CallObservationEndedEv) ev).getCall();
                        callCaller.remove(call);
                        urlOpened.remove(call);
                        try { CallRegistry.getInstance().remove(call); } catch (Throwable _ignore) {}
                    } catch (Exception ignore) {}
                } else {
                    // Log other events at DEBUG level
                    String other = "Event: " + ev;
                    System.out.println(other);
                    writeLog(other);
                }
            } catch (Exception outer) {
                String err = "Unhandled exception processing event " + ev + ": " + outer.getMessage();
                System.err.println(err);
                writeLog(err);
            }
        }
    }

    private void openUrlWithNumber(String template, String number) {
        // Respect global UI setting to enable/disable screen pops
        try {
            if (!JTAPIGui.isUrlPopEnabled()) {
                writeLog("Screen pop disabled by user; not opening URL for " + number);
                return;
            }
        } catch (Throwable _ignore) {
            // if JTAPIGui not available, fall back to opening
        }
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

    /**
     * Determines if a call is outbound (originated from this terminal)
     * @param call The call to check
     * @param callingNumber The calling number associated with the call
     * @return true if the call is outbound, false if inbound or unknown
     */
    private boolean isOutboundCall(Call call, String callingNumber) {
        if (call == null || callingNumber == null || this.monitoredAddress == null) {
            writeLog("isOutboundCall: false (null params) - call=" + call + " callingNumber=" + callingNumber + " monitoredAddress=" + this.monitoredAddress);
            return false;
        }

        try {
            // Primary check: if the calling number matches our monitored address, it's an outbound call
            if (callingNumber.equalsIgnoreCase(this.monitoredAddress)) {
                writeLog("isOutboundCall: true (calling number matches monitored address) - " + callingNumber + " == " + this.monitoredAddress);
                return true;
            }

            // Additional check: if the calling number contains our monitored address
            if (callingNumber.toLowerCase().contains(this.monitoredAddress.toLowerCase())) {
                writeLog("isOutboundCall: true (calling number contains monitored address) - " + callingNumber + " contains " + this.monitoredAddress);
                return true;
            }

            // Check connections for origination clues
            Connection[] connections = call.getConnections();
            if (connections != null) {
                for (Connection conn : connections) {
                    if (conn != null) {
                        try {
                            Address connAddr = conn.getAddress();
                            if (connAddr != null) {
                                String connAddrName = connAddr.getName();
                                if (connAddrName != null && connAddrName.equalsIgnoreCase(this.monitoredAddress)) {
                                    // If one of the connections is our monitored address and calling number is different,
                                    // this could be either inbound or outbound - need to check direction
                                    if (!callingNumber.equalsIgnoreCase(this.monitoredAddress)) {
                                        // The calling number is different from monitored address but one connection is monitored address
                                        // This suggests it's an inbound call (external caller to monitored address)
                                        writeLog("isOutboundCall: false (external caller to monitored address) - callingNumber=" + callingNumber + " connAddr=" + connAddrName);
                                        return false;
                                    }
                                    writeLog("isOutboundCall: true (monitored address in connections) - connAddr=" + connAddrName);
                                    return true;
                                }
                            }
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }

        return false;
    }

    // GUI popup removed for production; use system notifications or external caller if needed.
}
