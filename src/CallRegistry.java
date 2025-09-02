import javax.telephony.Call;
import javax.telephony.Connection;
import javax.telephony.TerminalConnection;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.lang.reflect.Method;

/**
 * Lightweight registry for active Calls seen by JTAPICallerInfo.
 * GUI components can listen for changes and request control actions.
 */
public class CallRegistry {
    public static class CallInfo {
        public final Call call;
        public volatile String number;
        public volatile String state;
        public volatile String address;
        public volatile long lastSeen = System.currentTimeMillis();
        
        // Track original call info for Cisco provider that invalidates held calls
        public volatile String originalNumber;
        public volatile String originalAddress;
        public volatile boolean wasHeld = false;

        public CallInfo(Call call, String number, String state, String address) {
            this.call = call;
            this.number = number;
            this.state = state;
            this.address = address;
            this.originalNumber = number;
            this.originalAddress = address;
        }
    }

    public interface Listener {
        void onCallAdded(CallInfo info);
        void onCallUpdated(CallInfo info);
        void onCallRemoved(CallInfo info);
    }

    private final Map<Call, CallInfo> calls = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    
    // Track held calls by their string representation to survive Cisco call invalidation
    private final Map<String, Boolean> heldCallTracker = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, String> originalNumberTracker = Collections.synchronizedMap(new HashMap<>());
    
    // Flag to disable park methods for true hold functionality
    private static final boolean DISABLE_PARK_METHODS = true;
    
    // Unique identifier to track if multiple instances are created
    private final String instanceId = java.util.UUID.randomUUID().toString();

    private String getCallId(Call call) {
        String callStr = String.valueOf(call);
        // Prefer the GCID tuple only, e.g., "GCID=(2,5774305)"
        try {
            int gcidStart = callStr.indexOf("GCID=(");
            if (gcidStart >= 0) {
                int parenClose = callStr.indexOf(')', gcidStart);
                if (parenClose > gcidStart) {
                    return callStr.substring(gcidStart, parenClose + 1);
                }
            }
        } catch (Exception ignore) {}
        // Fallback: strip volatile state suffix like "->ACTIVE" or "->INVALID"
        int arrowIndex = callStr.lastIndexOf("->");
        if (arrowIndex > 0) {
            return callStr.substring(0, arrowIndex);
        }
        return callStr;
    }

    private static final CallRegistry INSTANCE = new CallRegistry();

    public static CallRegistry getInstance() { return INSTANCE; }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public CallInfo addOrUpdate(Call call, String number, String state, String address) {
        if (call == null) return null;

        String callId = getCallId(call); // Use stable call ID (GCID)
        CallInfo info;
        synchronized (calls) {
            info = calls.get(call);
            if (info == null) {
                // Debug: show tracker status and sizes before creating CallInfo
                boolean trackerHas = heldCallTracker.containsKey(callId);
                Boolean trackerVal = heldCallTracker.get(callId);
                String trackerNum = originalNumberTracker.get(callId);
                System.out.println("REGISTRY: [" + instanceId + "] NEW CallInfo path. callId=" + callId + 
                        ", trackerHas=" + trackerHas + ", trackerVal=" + trackerVal + ", trackerNum=" + trackerNum +
                        ", heldSize=" + heldCallTracker.size() + ", numSize=" + originalNumberTracker.size());

                // Try to adopt an existing entry with same GCID (provider may invalidate and create a new Call object)
                Call adoptKey = null;
                CallInfo adoptInfo = null;
                System.out.println("REGISTRY: [" + instanceId + "] Looking for existing calls to adopt, current calls size: " + calls.size());
                for (Map.Entry<Call, CallInfo> e : calls.entrySet()) {
                    try {
                        String existingCallId = getCallId(e.getKey());
                        System.out.println("REGISTRY: [" + instanceId + "] Checking existing call: " + e.getKey() + " with callId: " + existingCallId + ", wasHeld: " + e.getValue().wasHeld);
                        if (callId.equals(existingCallId)) {
                            adoptKey = e.getKey();
                            adoptInfo = e.getValue();
                            System.out.println("REGISTRY: [" + instanceId + "] Found matching call to adopt: " + adoptKey + ", wasHeld: " + adoptInfo.wasHeld);
                            break;
                        }
                    } catch (Exception ignore) {}
                }

                info = new CallInfo(call, number, state, address);

                if (adoptInfo != null) {
                    // Carry over held/original info from previous Call mapping
                    info.wasHeld = adoptInfo.wasHeld;
                    if (adoptInfo.originalNumber != null) info.originalNumber = adoptInfo.originalNumber;
                    calls.remove(adoptKey);
            System.out.println("REGISTRY: Adopted previous CallInfo by GCID. wasHeld=" + info.wasHeld +
                ", originalNumber=" + info.originalNumber + ", from=" + adoptKey + ")");
                } else if (trackerVal != null && trackerVal) {
                    // Fallback: use tracker if present
                    info.wasHeld = true;
                    info.originalNumber = trackerNum != null ? trackerNum : number;
                    System.out.println("REGISTRY: Restored held call info via tracker - wasHeld: true, originalNumber: " + info.originalNumber + ", callId: " + callId);
                }

                calls.put(call, info);
                System.out.println("REGISTRY: Created NEW CallInfo for " + call + " - state: " + state + ", wasHeld: " + info.wasHeld);
                for (Listener l : listeners) try { l.onCallAdded(info); } catch (Exception ignore) {}
            } else {
                System.out.println("REGISTRY: Updating EXISTING CallInfo for " + call + " - state: " + state + ", wasHeld: " + info.wasHeld);
                info.number = number != null ? number : info.number;
                info.state = state != null ? state : info.state;
                info.address = address != null ? address : info.address;
                info.lastSeen = System.currentTimeMillis();
                System.out.println("REGISTRY: After update - wasHeld: " + info.wasHeld + ", originalNumber: " + info.originalNumber);
                for (Listener l : listeners) try { l.onCallUpdated(info); } catch (Exception ignore) {}
            }
        }
        return info;
    }

    public void remove(Call call) {
        if (call == null) return;
        
        String callId = getCallId(call); // Use stable call ID
        
        // Don't clean up tracker if call is just invalid (Cisco behavior)
        // Only clean up when call is truly ended
        try {
            if (call.getState() == Call.INVALID) {
                System.out.println("REMOVE: Call is INVALID, preserving tracker for potential resume");
                CallInfo info;
                synchronized (calls) {
                    info = calls.remove(call);
                }
                if (info != null) for (Listener l : listeners) try { l.onCallRemoved(info); } catch (Exception ignore) {}
                return; // Don't clean up tracker
            }
        } catch (Exception e) {
            System.out.println("REMOVE: Could not check call state: " + e.getMessage());
        }
        
        CallInfo info;
        synchronized (calls) {
            info = calls.remove(call);
        }
        
        // Clean up trackers only for truly ended calls
        heldCallTracker.remove(callId);
        originalNumberTracker.remove(callId);
        System.out.println("REMOVE: Cleaned up trackers for callId: " + callId + ", tracker sizes now - held: " + heldCallTracker.size() + ", num: " + originalNumberTracker.size());
        
        if (info != null) for (Listener l : listeners) try { l.onCallRemoved(info); } catch (Exception ignore) {}
    }

    public List<CallInfo> snapshot() {
        synchronized (calls) {
            return new ArrayList<>(calls.values());
        }
    }

    // Best-effort: attempt to answer/pick-up a call. Returns true if any reflective method succeeded.
    public boolean pickCall(Call call) {
        if (call == null) return false;
        try {
            Connection[] conns = call.getConnections();
            if (conns != null) {
                for (Connection c : conns) {
                    // try TerminalConnection answer/pickup via reflection
                    try {
                        TerminalConnection[] tcs = c.getTerminalConnections();
                        if (tcs != null) {
                            for (TerminalConnection tc : tcs) {
                                Method[] m = tc.getClass().getMethods();
                                for (Method mm : m) {
                                    String n = mm.getName().toLowerCase();
                                    if (n.contains("answer") || n.contains("pickup") || n.contains("pickup") || n.contains("pickupcall") || n.contains("pickUp")) {
                                                        mm.setAccessible(true);
                                                        mm.invoke(tc);
                                                        // mark this call as connected
                                                        setState(call, "CONNECTED");
                                                        // best-effort: hold other connected calls
                                                        synchronized (calls) {
                                                            for (Call other : new ArrayList<>(calls.keySet())) {
                                                                if (!other.equals(call)) {
                                                                    CallInfo ci = calls.get(other);
                                                                    if (ci != null && "CONNECTED".equalsIgnoreCase(ci.state)) {
                                                                        try { holdCall(other); } catch (Exception _ignore) {}
                                                                    }
                                                                }
                                                            }
                                                        }
                                                        return true;
                                                    }
                                }
                            }
                        }
                    } catch (Exception ignore) {}
                }
            }
            // fallback: try call-level methods
            Method[] m = call.getClass().getMethods();
            for (Method mm : m) {
                String n = mm.getName().toLowerCase();
                if (n.contains("answer") || n.contains("pickup") || n.contains("pickUp") || n.contains("connect")) {
                    mm.setAccessible(true);
                    mm.invoke(call);
                    setState(call, "CONNECTED");
                    synchronized (calls) {
                        for (Call other : new ArrayList<>(calls.keySet())) {
                            if (!other.equals(call)) {
                                CallInfo ci = calls.get(other);
                                if (ci != null && "CONNECTED".equalsIgnoreCase(ci.state)) {
                                    try { holdCall(other); } catch (Exception _ignore) {}
                                }
                            }
                        }
                    }
                    return true;
                }
            }
        } catch (Exception e) {
            // ignore, return false below
        }
        return false;
    }

    // Best-effort: attempt to disconnect/hangup a call.
    public boolean disconnectCall(Call call) {
        if (call == null) return false;
        try {
            // Try CallControlCall.drop or disconnect-like methods via reflection
            Method[] m = call.getClass().getMethods();
            for (Method mm : m) {
                String n = mm.getName().toLowerCase();
                if (n.equals("drop") || n.contains("disconnect") || n.equals("release") || n.equals("clear")) {
                    mm.setAccessible(true);
                    mm.invoke(call);
                    return true;
                }
            }
            // Fallback: attempt to disconnect each Connection
            Connection[] conns = call.getConnections();
            if (conns != null) {
                for (Connection c : conns) {
                    Method[] cm = c.getClass().getMethods();
                    for (Method mm : cm) {
                        String n = mm.getName().toLowerCase();
                        if (n.equals("disconnect") || n.equals("drop") || n.equals("release") || n.equals("clear")) {
                            mm.setAccessible(true);
                            mm.invoke(c);
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    // Best-effort: transfer call to target number. Tries common transfer methods.
    public boolean transferCall(Call call, String target) {
        if (call == null || target == null) return false;
        try {
            Method[] m = call.getClass().getMethods();
            for (Method mm : m) {
                String n = mm.getName().toLowerCase();
                if (n.contains("transfer") && mm.getParameterCount() == 1 && mm.getParameterTypes()[0] == java.lang.String.class) {
                    mm.setAccessible(true);
                    mm.invoke(call, target);
                    return true;
                }
            }
            // Try call-control style: some implementations expose transfer on a connection
            Connection[] conns = call.getConnections();
            if (conns != null) {
                for (Connection c : conns) {
                    Method[] cm = c.getClass().getMethods();
                    for (Method mm : cm) {
                        String n = mm.getName().toLowerCase();
                        if (n.contains("transfer") && mm.getParameterCount() == 1 && mm.getParameterTypes()[0] == java.lang.String.class) {
                            mm.setAccessible(true);
                            mm.invoke(c, target);
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    // Best-effort: conference call with target number. Tries common conference methods.
    public boolean conferenceCall(Call call, String target) {
        if (call == null || target == null) return false;
        try {
            Method[] m = call.getClass().getMethods();
            for (Method mm : m) {
                String n = mm.getName().toLowerCase();
                if ((n.contains("conference") || n.contains("join") || n.contains("merge")) &&
                    mm.getParameterCount() == 1 && mm.getParameterTypes()[0] == java.lang.String.class) {
                    mm.setAccessible(true);
                    mm.invoke(call, target);
                    return true;
                }
            }
            // Try call-control style: some implementations expose conference on a connection
            Connection[] conns = call.getConnections();
            if (conns != null) {
                for (Connection c : conns) {
                    Method[] cm = c.getClass().getMethods();
                    for (Method mm : cm) {
                        String n = mm.getName().toLowerCase();
                        if ((n.contains("conference") || n.contains("join") || n.contains("merge")) &&
                            mm.getParameterCount() == 1 && mm.getParameterTypes()[0] == java.lang.String.class) {
                            mm.setAccessible(true);
                            mm.invoke(c, target);
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    // Best-effort: dial/make a new call to target number.
    public boolean dialCall(String target) {
        if (target == null || target.trim().isEmpty()) return false;
        try {
            // Get the provider and create a new call
            javax.telephony.Provider provider = getProvider();
            if (provider == null) return false;

            // Create a new call
            Call call = provider.createCall();
            if (call == null) return false;

            // Get the monitored address
            javax.telephony.Address[] addresses = provider.getAddresses();
            if (addresses == null || addresses.length == 0) return false;

            // Use the first address (typically the monitored extension)
            javax.telephony.Address originatingAddress = addresses[0];

            // Add a CallObserver to track call state changes
            try {
                JTAPICallerInfo callObserver = new JTAPICallerInfo(null, "CONNECTED", originatingAddress.getName());
                call.addObserver(callObserver);
                System.out.println("DIAL: Added CallObserver to outbound call");
            } catch (Exception e) {
                System.err.println("DIAL: Failed to add CallObserver: " + e.getMessage());
            }

            // Connect the call
            call.connect(provider.getTerminals()[0], originatingAddress, target);

            // Add to registry so it appears in the UI - set ALERTING for outbound calls since they start dialing immediately
            addOrUpdate(call, target, "ALERTING", originatingAddress.getName());

            return true;
        } catch (Exception e) {
            System.err.println("Failed to dial call: " + e.getMessage());
        }
        return false;
    }

    // Helper method to get the JTAPI provider
    private javax.telephony.Provider getProvider() {
        // Get the provider from JTAPIGui
        return JTAPIGui.getCurrentProvider();
    }

    // Best-effort: attempt to place a call on hold.
    public boolean holdCall(Call call) {
        if (call == null) return false;
        
        String callId = getCallId(call); // Use stable call ID
        
        // Store original call info before hold (in case Cisco invalidates it)
        CallInfo info = calls.get(call);
        if (info != null) {
            info.originalNumber = info.number;
            info.originalAddress = info.address;
            System.out.println("HOLD: Stored original info - number: " + info.originalNumber + ", address: " + info.originalAddress);
            
            // Also store in persistent tracker
            heldCallTracker.put(callId, true);
            originalNumberTracker.put(callId, info.originalNumber);
            System.out.println("HOLD: [" + instanceId + "] Saved to tracker - callId: " + callId + ", number: " + info.originalNumber);
            System.out.println("HOLD: [" + instanceId + "] Tracker sizes after save - held: " + heldCallTracker.size() + ", num: " + originalNumberTracker.size());
            System.out.println("HOLD: [" + instanceId + "] Tracker contents - held: " + heldCallTracker.keySet() + ", num: " + originalNumberTracker.keySet());
        } else {
            System.out.println("HOLD: No CallInfo found for call: " + call);
        }
        
        try {
            // Try call-level hold methods - prioritize "hold" over "park"
            Method[] m = call.getClass().getMethods();
            
            // Debug: Log all available methods (excluding getters)
            System.out.println("HOLD: Available call methods (excluding getters):");
            for (Method debugMethod : m) {
                String methodName = debugMethod.getName().toLowerCase();
                // Exclude getter methods and focus on action methods
                if ((methodName.contains("hold") || methodName.contains("park") || methodName.contains("consult") ||
                     methodName.contains("transfer") || methodName.contains("suspend") || methodName.contains("mute") ||
                     methodName.contains("pause")) &&
                    !methodName.startsWith("get") && !methodName.startsWith("is") && !methodName.startsWith("can")) {
                    System.out.println("HOLD: Found action method: " + debugMethod.getName() + " params: " + debugMethod.getParameterCount());
                }
            }
            
            // First pass: try "hold" methods only (excluding getters)
            for (Method mm : m) {
                String n = mm.getName().toLowerCase();
                if (n.contains("hold") && !n.startsWith("get") && !n.startsWith("is") && !n.startsWith("can") && mm.getParameterCount() == 0) {
                    mm.setAccessible(true);
                    mm.invoke(call);
                    setState(call, "HOLD");
                    if (info != null) {
                        info.wasHeld = true;
                        System.out.println("HOLD: Set wasHeld=true for call: " + call + " using hold method: " + mm.getName());
                    }
                    return true;
                }
            }
            
            // Second pass: try "consult" methods with parameters (Cisco consultative hold)
            for (Method mm : m) {
                String n = mm.getName().toLowerCase();
                if (n.equals("consult") && !n.startsWith("get") && !n.startsWith("is") && !n.startsWith("can")) {
                    try {
                        mm.setAccessible(true);
                        Class<?>[] paramTypes = mm.getParameterTypes();
                        System.out.println("HOLD: Trying consult method with " + paramTypes.length + " parameters");
                        
                        // Try different parameter combinations for consult
                        System.out.println("HOLD: Consult method " + mm.getName() + " expects " + paramTypes.length + " parameters:");
                        for (int i = 0; i < paramTypes.length; i++) {
                            System.out.println("HOLD:   Param " + i + ": " + paramTypes[i].getName());
                        }
                        
                        if (paramTypes.length == 1) {
                            // consult(TerminalConnection) - try with current terminal connection
                            Connection[] conns = call.getConnections();
                            if (conns != null && conns.length > 0) {
                                TerminalConnection[] tcs = conns[0].getTerminalConnections();
                                if (tcs != null && tcs.length > 0) {
                                    try {
                                        mm.invoke(call, tcs[0]);
                                        setState(call, "HOLD");
                                        if (info != null) {
                                            info.wasHeld = true;
                                            System.out.println("HOLD: Set wasHeld=true for call: " + call + " using consult(terminalConnection) method: " + mm.getName());
                                        }
                                        return true;
                                    } catch (Exception e) {
                                        System.out.println("HOLD: consult(terminalConnection) failed: " + e.getMessage());
                                    }
                                }
                            }
                        } else if (paramTypes.length == 2) {
                            // consult(TerminalConnection, Address) - try with current terminal connection and address
                            Connection[] conns = call.getConnections();
                            if (conns != null && conns.length > 0) {
                                TerminalConnection[] tcs = conns[0].getTerminalConnections();
                                if (tcs != null && tcs.length > 0) {
                                    try {
                                        mm.invoke(call, tcs[0], conns[0].getAddress().getName());
                                        setState(call, "HOLD");
                                        if (info != null) {
                                            info.wasHeld = true;
                                            System.out.println("HOLD: Set wasHeld=true for call: " + call + " using consult(terminalConnection,address) method: " + mm.getName());
                                        }
                                        return true;
                                    } catch (Exception e) {
                                        System.out.println("HOLD: consult(terminalConnection,address) failed: " + e.getMessage());
                                    }
                                }
                            }
                        } else if (paramTypes.length == 3) {
                            // consult(TerminalConnection, Address, CiscoRTPParams) - try with terminal connection, address, and null RTP params
                            Connection[] conns = call.getConnections();
                            if (conns != null && conns.length > 0) {
                                TerminalConnection[] tcs = conns[0].getTerminalConnections();
                                if (tcs != null && tcs.length > 0) {
                                    try {
                                        mm.invoke(call, tcs[0], conns[0].getAddress().getName(), null);
                                        setState(call, "HOLD");
                                        if (info != null) {
                                            info.wasHeld = true;
                                            System.out.println("HOLD: Set wasHeld=true for call: " + call + " using consult(terminalConnection,address,rtpParams) method: " + mm.getName());
                                        }
                                        return true;
                                    } catch (Exception e) {
                                        System.out.println("HOLD: consult(terminalConnection,address,rtpParams) failed: " + e.getMessage());
                                    }
                                }
                            }
                        } else if (paramTypes.length == 4) {
                            // consult(TerminalConnection, Address, boolean, CiscoRTPParams) - try with terminal connection, address, false, and null RTP params
                            Connection[] conns = call.getConnections();
                            if (conns != null && conns.length > 0) {
                                TerminalConnection[] tcs = conns[0].getTerminalConnections();
                                if (tcs != null && tcs.length > 0) {
                                    try {
                                        mm.invoke(call, tcs[0], conns[0].getAddress().getName(), false, null);
                                        setState(call, "HOLD");
                                        if (info != null) {
                                            info.wasHeld = true;
                                            System.out.println("HOLD: Set wasHeld=true for call: " + call + " using consult(terminalConnection,address,boolean,rtpParams) method: " + mm.getName());
                                        }
                                        return true;
                                    } catch (Exception e) {
                                        System.out.println("HOLD: consult(terminalConnection,address,boolean,rtpParams) failed: " + e.getMessage());
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        System.out.println("HOLD: Consult method " + mm.getName() + " failed: " + e.getMessage());
                    }
                }
            }
            
            // Third pass: try "transfer" methods (some Cisco implementations use transfer for hold)
            for (Method mm : m) {
                String n = mm.getName().toLowerCase();
                if (n.equals("transfer") && !n.startsWith("get") && !n.startsWith("is") && !n.startsWith("can")) {
                    try {
                        mm.setAccessible(true);
                        Class<?>[] paramTypes = mm.getParameterTypes();
                        System.out.println("HOLD: Trying transfer method with " + paramTypes.length + " parameters");
                        
                        if (paramTypes.length == 1 && paramTypes[0] == String.class) {
                            // transfer(String) - try with empty string or special hold destination
                            try {
                                mm.invoke(call, "");
                                setState(call, "HOLD");
                                if (info != null) {
                                    info.wasHeld = true;
                                    System.out.println("HOLD: Set wasHeld=true for call: " + call + " using transfer(empty) method: " + mm.getName());
                                }
                                return true;
                            } catch (Exception e) {
                                System.out.println("HOLD: transfer(empty) failed: " + e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        System.out.println("HOLD: Transfer method " + mm.getName() + " failed: " + e.getMessage());
                    }
                }
            }
            
            // Try per-connection hold/retrieve methods - prioritize "hold" over "park"
            Connection[] conns = call.getConnections();
            if (conns != null) {
                for (Connection c : conns) {
                    Method[] cm = c.getClass().getMethods();
                    
                    // Debug: Log all available connection methods (excluding getters)
                    System.out.println("HOLD: Available connection methods for " + c.getClass().getName() + " (excluding getters):");
                    for (Method debugMethod : cm) {
                        String methodName = debugMethod.getName().toLowerCase();
                        if ((methodName.contains("hold") || methodName.contains("park") || methodName.contains("consult") ||
                             methodName.contains("transfer") || methodName.contains("suspend") || methodName.contains("mute") ||
                             methodName.contains("pause")) &&
                            !methodName.startsWith("get") && !methodName.startsWith("is") && !methodName.startsWith("can")) {
                            System.out.println("HOLD: Found connection action method: " + debugMethod.getName() + " params: " + debugMethod.getParameterCount());
                        }
                    }
                    
                    // First pass: try "hold" methods only
                    for (Method mm : cm) {
                        String n = mm.getName().toLowerCase();
                        if (n.contains("hold") && !n.startsWith("get") && !n.startsWith("is") && !n.startsWith("can") && mm.getParameterCount() == 0) {
                            mm.setAccessible(true);
                            mm.invoke(c);
                            setState(call, "HOLD");
                            if (info != null) {
                                info.wasHeld = true;
                                System.out.println("HOLD: Set wasHeld=true for call via connection: " + call + " using hold method: " + mm.getName());
                            }
                            return true;
                        }
                    }
                    
                    // Second pass: if no hold method found, try "park" methods (if not disabled)
                    if (!DISABLE_PARK_METHODS) {
                        for (Method mm : cm) {
                            String n = mm.getName().toLowerCase();
                            if (n.contains("park") && !n.startsWith("get") && !n.startsWith("is") && !n.startsWith("can") && mm.getParameterCount() == 0) {
                                mm.setAccessible(true);
                                mm.invoke(c);
                                setState(call, "HOLD");
                                if (info != null) {
                                    info.wasHeld = true;
                                    System.out.println("HOLD: Set wasHeld=true for call via connection: " + call + " using park method: " + mm.getName());
                                }
                                return true;
                            }
                        }
                    } else {
                        System.out.println("HOLD: Park methods disabled, skipping park methods");
                    }
                    
                    // Third pass: try "consult" methods on connection
                    for (Method mm : cm) {
                        String n = mm.getName().toLowerCase();
                        if (n.equals("consult") && !n.startsWith("get") && !n.startsWith("is") && !n.startsWith("can")) {
                            try {
                                mm.setAccessible(true);
                                Class<?>[] paramTypes = mm.getParameterTypes();
                                System.out.println("HOLD: Trying connection consult method with " + paramTypes.length + " parameters");
                                
                                if (paramTypes.length == 1) {
                                    // consult(Terminal)
                                    TerminalConnection[] tcs = c.getTerminalConnections();
                                    if (tcs != null && tcs.length > 0) {
                                        mm.invoke(c, tcs[0].getTerminal());
                                        setState(call, "HOLD");
                                        if (info != null) {
                                            info.wasHeld = true;
                                            System.out.println("HOLD: Set wasHeld=true for call via connection: " + call + " using consult(terminal) method: " + mm.getName());
                                        }
                                        return true;
                                    }
                                }
                            } catch (Exception e) {
                                System.out.println("HOLD: Connection consult method " + mm.getName() + " failed: " + e.getMessage());
                            }
                        }
                    }
                    
                    // Fourth pass: try other hold-related patterns on connection (excluding getters)
                    for (Method mm : cm) {
                        String n = mm.getName().toLowerCase();
                        if ((n.contains("hold") || n.contains("suspend") || n.contains("mute") || n.contains("pause")) &&
                            !n.startsWith("get") && !n.startsWith("is") && !n.startsWith("can") && mm.getParameterCount() == 0) {
                            mm.setAccessible(true);
                            mm.invoke(c);
                            setState(call, "HOLD");
                            if (info != null) {
                                info.wasHeld = true;
                                System.out.println("HOLD: Set wasHeld=true for call via connection: " + call + " using alternative method: " + mm.getName());
                            }
                            return true;
                        }
                    }
                }
            }
            
            // Try per-TerminalConnection hold methods - this is often the correct way in JTAPI
            if (conns != null) {
                for (Connection c : conns) {
                    TerminalConnection[] tcs = c.getTerminalConnections();
                    if (tcs != null) {
                        for (TerminalConnection tc : tcs) {
                            Method[] tcm = tc.getClass().getMethods();
                            
                            // Debug: Log available TerminalConnection methods
                            System.out.println("HOLD: Available TerminalConnection methods for " + tc.getClass().getName() + " (excluding getters):");
                            for (Method debugMethod : tcm) {
                                String methodName = debugMethod.getName().toLowerCase();
                                if ((methodName.contains("hold") || methodName.contains("setHeld") || methodName.contains("park") ||
                                     methodName.contains("consult") || methodName.contains("transfer") || methodName.contains("suspend")) &&
                                    !methodName.startsWith("get") && !methodName.startsWith("is") && !methodName.startsWith("can")) {
                                    System.out.println("HOLD: Found TerminalConnection action method: " + debugMethod.getName() + " params: " + debugMethod.getParameterCount());
                                }
                            }
                            
                            // First pass: try setHeld(boolean) method
                            for (Method mm : tcm) {
                                String n = mm.getName().toLowerCase();
                                if (n.equals("setheld") && mm.getParameterCount() == 1 && mm.getParameterTypes()[0] == boolean.class) {
                                    mm.setAccessible(true);
                                    mm.invoke(tc, true);
                                    setState(call, "HOLD");
                                    if (info != null) {
                                        info.wasHeld = true;
                                        System.out.println("HOLD: Set wasHeld=true for call via TerminalConnection: " + call + " using setHeld(true) method: " + mm.getName());
                                    }
                                    return true;
                                }
                            }
                            
                            // Second pass: try hold() method with no parameters
                            for (Method mm : tcm) {
                                String n = mm.getName().toLowerCase();
                                if (n.equals("hold") && !n.startsWith("get") && !n.startsWith("is") && !n.startsWith("can") && mm.getParameterCount() == 0) {
                                    mm.setAccessible(true);
                                    mm.invoke(tc);
                                    setState(call, "HOLD");
                                    if (info != null) {
                                        info.wasHeld = true;
                                        System.out.println("HOLD: Set wasHeld=true for call via TerminalConnection: " + call + " using hold() method: " + mm.getName());
                                    }
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    // Best-effort: attempt to resume/unhold a call.
    // For Cisco provider: since held calls become INVALID, we need special handling
    public boolean resumeCall(Call call) {
        if (call == null) return false;
        
        CallInfo info = calls.get(call);
        System.out.println("RESUME: Attempting to resume call: " + call);
        System.out.println("RESUME: Call class: " + call.getClass().getName());
        
        // Check if call is in INVALID state - typical for Cisco held calls
        try {
            int state = call.getState();
            System.out.println("RESUME: Call state: " + state);
            if (state == Call.INVALID) {
                System.out.println("RESUME: Call is INVALID - this is expected for Cisco held calls");
                
                // For invalid Cisco calls, we need to create a new call to the original number
                if (info != null) {
                    System.out.println("RESUME: CallInfo found - wasHeld: " + info.wasHeld + ", originalNumber: " + info.originalNumber);
                    if (info.wasHeld && info.originalNumber != null) {
                        System.out.println("RESUME: This was a held call with original number: " + info.originalNumber);
                        // Remove the invalid call from our registry since it can't be resumed
                        remove(call);
                        return false; // Let the UI handle the user message
                    } else {
                        System.out.println("RESUME: Call info incomplete - wasHeld: " + info.wasHeld + ", originalNumber: " + info.originalNumber);
                    }
                } else {
                    System.out.println("RESUME: No CallInfo found for invalid call");
                }
                return false;
            }
        } catch (Exception e) {
            System.out.println("RESUME: Could not get call state: " + e.getMessage());
        }
        
        // If call is not invalid, try standard resume methods
        try {
            // Try Cisco-specific CallControlCall.offHook() with parameters
            try {
                // Try offHook() with no parameters first
                Method offHook = call.getClass().getMethod("offHook");
                offHook.invoke(call);
                System.out.println("RESUME: Success with offHook()");
                return true;
            } catch (NoSuchMethodException e) {
                // Try offHook with parameters (Terminal, Address)
                try {
                    Connection[] conns = call.getConnections();
                    if (conns != null && conns.length > 0) {
                        TerminalConnection[] tcs = conns[0].getTerminalConnections();
                        if (tcs != null && tcs.length > 0) {
                            Method offHook = call.getClass().getMethod("offHook", 
                                Class.forName("javax.telephony.Terminal"), 
                                Class.forName("javax.telephony.Address"));
                            offHook.invoke(call, tcs[0].getTerminal(), conns[0].getAddress());
                            System.out.println("RESUME: Success with offHook(Terminal, Address)");
                            return true;
                        }
                    }
                } catch (Exception e2) {
                    System.out.println("RESUME: offHook(Terminal, Address) failed: " + e2.getMessage());
                }
            } catch (Exception ignore) {
                System.out.println("RESUME: offHook() not available or failed");
            }
            
            Connection[] conns = call.getConnections();
            if (conns != null) {
                for (Connection c : conns) {
                    System.out.println("RESUME: Trying connection: " + c.getClass().getName());
                    System.out.println("RESUME: Connection state: " + c.getState());
                    
                    // Skip connections that are not in a held-like state
                    try {
                        int state = c.getState();
                        System.out.println("RESUME: Connection state value: " + state);
                        // Common JTAPI connection states: IDLE=0, INPROGRESS=1, ALERTING=2, CONNECTED=3, DISCONNECTED=4, FAILED=5, UNKNOWN=6
                        // Held calls often show as CONNECTED but we'll try anyway
                    } catch (Exception e) {
                        System.out.println("RESUME: Could not get connection state: " + e.getMessage());
                    }
                    
                    // Try Cisco CallControlConnection.offHook() with parameters
                    try {
                        TerminalConnection[] tcs = c.getTerminalConnections();
                        if (tcs != null && tcs.length > 0) {
                            Method offHook = c.getClass().getMethod("offHook", 
                                Class.forName("javax.telephony.Terminal"));
                            offHook.invoke(c, tcs[0].getTerminal());
                            System.out.println("RESUME: Success with connection.offHook(Terminal)");
                            return true;
                        }
                    } catch (Exception ignore) {
                        System.out.println("RESUME: connection.offHook(Terminal) not available or failed");
                    }
                    
                    // Try standard connection offHook
                    try {
                        Method offHook = c.getClass().getMethod("offHook");
                        offHook.invoke(c);
                        System.out.println("RESUME: Success with connection.offHook()");
                        return true;
                    } catch (Exception ignore) {
                        System.out.println("RESUME: connection.offHook() not available or failed");
                    }
                    
                    // Try TerminalConnections
                    try {
                        TerminalConnection[] tcs = c.getTerminalConnections();
                        if (tcs != null) {
                            for (TerminalConnection tc : tcs) {
                                System.out.println("RESUME: Trying terminal connection: " + tc.getClass().getName());
                                
                                // Try CallControlTerminalConnection.unhold()
                                try {
                                    Method unhold = tc.getClass().getMethod("unhold");
                                    unhold.invoke(tc);
                                    System.out.println("RESUME: Success with tc.unhold()");
                                    return true;
                                } catch (Exception ignore) {
                                    System.out.println("RESUME: tc.unhold() not available or failed");
                                }
                                
                                // Try CallControlTerminalConnection.answer()
                                try {
                                    Method answer = tc.getClass().getMethod("answer");
                                    answer.invoke(tc);
                                    System.out.println("RESUME: Success with tc.answer()");
                                    return true;
                                } catch (Exception ignore) {
                                    System.out.println("RESUME: tc.answer() not available or failed");
                                }
                            }
                        }
                    } catch (Exception ignore) {}
                }
            }
            
            // Generic reflection fallback
            Method[] m = call.getClass().getMethods();
            System.out.println("RESUME: Available call methods:");
            for (Method mm : m) {
                String n = mm.getName().toLowerCase();
                if (n.contains("resume") || n.contains("retrieve") || n.contains("unhold") || n.contains("continue") || n.contains("offhook") || n.contains("unpark") || n.contains("consult") || n.contains("transfer")) {
                    System.out.println("RESUME: Found method: " + mm.getName() + " params: " + mm.getParameterCount());
                    if (mm.getParameterCount() == 0) {
                        try {
                            mm.setAccessible(true);
                            mm.invoke(call);
                            System.out.println("RESUME: Success with " + mm.getName());
                            return true;
                        } catch (Exception e) {
                            System.out.println("RESUME: Failed with " + mm.getName() + ": " + e.getMessage());
                        }
                    }
                }
            }

            // Try Cisco-specific park retrieval methods
            try {
                System.out.println("RESUME: Trying Cisco-specific park retrieval methods");
                // Try to find park slot information from the call
                String parkSlot = null;
                try {
                    // Look for park-related properties or methods
                    Method[] callMethods = call.getClass().getMethods();
                    for (Method method : callMethods) {
                        String methodName = method.getName().toLowerCase();
                        if (methodName.contains("park") && method.getParameterCount() == 0) {
                            try {
                                Object result = method.invoke(call);
                                if (result != null) {
                                    parkSlot = result.toString();
                                    System.out.println("RESUME: Found park slot: " + parkSlot);
                                    break;
                                }
                            } catch (Exception e) {
                                // Continue looking
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println("RESUME: Could not determine park slot: " + e.getMessage());
                }

                // If we found a park slot, try to retrieve it
                if (parkSlot != null) {
                    // Try to retrieve the parked call
                    for (Method mm : m) {
                        String n = mm.getName().toLowerCase();
                        if (n.contains("retrieve") || n.contains("unpark")) {
                            try {
                                mm.setAccessible(true);
                                if (mm.getParameterCount() == 1) {
                                    mm.invoke(call, parkSlot);
                                } else {
                                    mm.invoke(call);
                                }
                                System.out.println("RESUME: Success retrieving parked call with " + mm.getName());
                                return true;
                            } catch (Exception e) {
                                System.out.println("RESUME: Failed retrieving parked call with " + mm.getName() + ": " + e.getMessage());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("RESUME: Exception during park retrieval: " + e.getMessage());
            }
            
        } catch (Exception e) {
            System.out.println("RESUME: Exception during resume: " + e.getMessage());
        }
        
        System.out.println("RESUME: All resume attempts failed");
        return false;
    }

    private void setState(Call call, String state) {
        if (call == null) return;
        synchronized (calls) {
            CallInfo ci = calls.get(call);
            if (ci != null) {
                ci.state = state;
                ci.lastSeen = System.currentTimeMillis();
                for (Listener l : listeners) try { l.onCallUpdated(ci); } catch (Exception ignore) {}
            }
        }
    }
}
