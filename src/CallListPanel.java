import javax.swing.*;
import javax.telephony.Call;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import javax.swing.Timer;

public class CallListPanel extends JPanel implements CallRegistry.Listener {
    private final JTable table;
    private final CallTableModel model;
    // blinking state for new calls
    private final Map<Call, Timer> blinkTimers = new HashMap<>();
    private final Map<Call, Boolean> blinkOn = new HashMap<>();
    // Track talk time for each call
    private final Map<Call, Long> callStartTimes = new HashMap<>();
    private final Map<Call, Timer> talkTimers = new HashMap<>();
    // Track hold time for each call
    private final Map<Call, Long> holdStartTimes = new HashMap<>();
    private final Map<Call, Timer> holdTimers = new HashMap<>();
    // Track total accumulated times (talk + hold)
    private final Map<Call, Long> totalTalkTime = new HashMap<>();
    private final Map<Call, Long> totalHoldTime = new HashMap<>();
    // Button references
    private JButton holdResumeBtn;
    private JButton pickBtn;
    private JButton hangupBtn;
    // Status label for user feedback
    private JLabel statusLabel;

    public CallListPanel() {
        super(new BorderLayout());
        model = new CallTableModel(this);
        table = new JTable(model);

        // Configure table appearance
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(25);
        table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
        table.setFont(new Font("SansSerif", Font.PLAIN, 11));
        table.setGridColor(Color.LIGHT_GRAY);
        table.setShowGrid(true);

        // Add table selection listener for better UX
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                // Only update UI when selection actually changes
                SwingUtilities.invokeLater(() -> {
                    updateHoldResumeButtonText();
                    updateButtonStates();
                });
            }
        });

        // Add mouse listener for double-click to pick up calls
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) { // Double-click
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        table.setRowSelectionInterval(row, row);
                        CallRegistry.CallInfo ci = model.getAt(row);
                        if (ci != null && ("ALERTING".equalsIgnoreCase(ci.state) ||
                                          "CREATED".equalsIgnoreCase(ci.state) ||
                                          "RINGING".equalsIgnoreCase(ci.state))) {
                            doPick();
                        }
                    }
                }
            }
        });

        // Set column widths - adjust for new time columns
        table.getColumnModel().getColumn(0).setPreferredWidth(100); // From
        table.getColumnModel().getColumn(1).setPreferredWidth(100); // To
        table.getColumnModel().getColumn(2).setPreferredWidth(70);  // State
        table.getColumnModel().getColumn(3).setPreferredWidth(70);  // Talk Time
        table.getColumnModel().getColumn(4).setPreferredWidth(70);  // Hold Time
        table.getColumnModel().getColumn(5).setPreferredWidth(70);  // Total Time

        // Create control panel with better layout
        JPanel controlPanel = new JPanel(new BorderLayout(10, 5));
        controlPanel.setBackground(new Color(240, 240, 240));
        controlPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        buttonPanel.setBackground(new Color(240, 240, 240));

        JButton pickBtn = createStyledButton("Pick", new Color(40, 167, 69));
        holdResumeBtn = createStyledButton("Hold/Resume", new Color(255, 193, 7));
        JButton hangupBtn = createStyledButton("Hangup", new Color(220, 53, 69));

        // Store button references
        this.pickBtn = pickBtn;
        this.hangupBtn = hangupBtn;

        pickBtn.addActionListener(_ -> doPick());
        holdResumeBtn.addActionListener(_ -> doHoldResume());
        hangupBtn.addActionListener(_ -> doHangup());

        buttonPanel.add(pickBtn);
        buttonPanel.add(holdResumeBtn);
        buttonPanel.add(hangupBtn);

        // Status label
        statusLabel = new JLabel("Select a call to perform actions", SwingConstants.CENTER);
        statusLabel.setFont(new Font("SansSerif", Font.ITALIC, 10));
        statusLabel.setForeground(Color.GRAY);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));

        controlPanel.add(buttonPanel, BorderLayout.CENTER);
        controlPanel.add(statusLabel, BorderLayout.SOUTH);

        // Add control panel at top
        add(controlPanel, BorderLayout.NORTH);

        // Add table with scroll pane
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(200, 200, 200), 1),
            "Active Calls",
            javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
            javax.swing.border.TitledBorder.DEFAULT_POSITION,
            new Font("SansSerif", Font.BOLD, 12)
        ));
        add(scrollPane, BorderLayout.CENTER);

        // Row renderer to color state column and add visual indicators
        table.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                java.awt.Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

                CallRegistry.CallInfo ci = null;
                try { ci = model.getAt(row); } catch (Exception _ignore) {}

                if (ci != null) {
                    // Set background color based on state
                    if ("HOLD".equalsIgnoreCase(ci.state) || "ON HOLD".equalsIgnoreCase(ci.state)) {
                        c.setBackground(new Color(255, 243, 205)); // Light yellow
                        c.setForeground(Color.BLACK);
                    } else if ("CONNECTED".equalsIgnoreCase(ci.state) || "TALKING".equalsIgnoreCase(ci.state)) {
                        c.setBackground(new Color(212, 237, 218)); // Light green
                        c.setForeground(Color.BLACK);
                    } else if ("ALERTING".equalsIgnoreCase(ci.state) || "CREATED".equalsIgnoreCase(ci.state) || "RINGING".equalsIgnoreCase(ci.state)) {
                        Boolean blink = blinkOn.get(ci.call);
                        if (Boolean.TRUE.equals(blink)) {
                            c.setBackground(new Color(255, 230, 230)); // Light red blink
                        } else {
                            c.setBackground(Color.WHITE);
                        }
                        c.setForeground(Color.BLACK);
                    } else {
                        c.setBackground(Color.WHITE);
                        c.setForeground(Color.BLACK);
                    }

                    // Highlight selected row
                    if (isSelected) {
                        c.setBackground(c.getBackground().darker());
                    }

                    // Special formatting for state column
                    if (column == 2) {
                        String stateText = "";
                        if ("HOLD".equalsIgnoreCase(ci.state)) {
                            stateText = "ON HOLD";
                        } else if ("CONNECTED".equalsIgnoreCase(ci.state) || "TALKING".equalsIgnoreCase(ci.state)) {
                            stateText = "TALKING";
                        } else if ("ALERTING".equalsIgnoreCase(ci.state) || "CREATED".equalsIgnoreCase(ci.state) || "RINGING".equalsIgnoreCase(ci.state)) {
                            stateText = "RINGING";
                        } else {
                            stateText = ci.state != null ? ci.state : "";
                        }
                        super.setText(stateText);
                    }
                } else {
                    c.setBackground(Color.WHITE);
                    c.setForeground(Color.BLACK);
                }

                return c;
            }
        });

        // Double-click behavior: smart call handling
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int r = table.getSelectedRow();
                    if (r >= 0) {
                        CallRegistry.CallInfo ci = model.getAt(r);
                        if (ci != null && ci.state != null) {
                            String s = ci.state.toLowerCase();
                            if (s.contains("hold")) {
                                // Held call -> resume
                                doResume();
                            } else if (s.contains("connected") || s.contains("talking")) {
                                // Active call -> hold
                                doHold();
                            } else if (s.contains("alerting") || s.contains("created") || s.contains("ringing")) {
                                // Ringing call -> pick
                                doPick();
                            }
                        } else {
                            doPick(); // Default to pick if state unknown
                        }
                    }
                }
            }
        });

        // Register for updates
        CallRegistry.getInstance().addListener(this);

        // Initial snapshot
        List<CallRegistry.CallInfo> snap = CallRegistry.getInstance().snapshot();
        for (CallRegistry.CallInfo ci : snap) {
            model.addOrUpdate(ci);
            if ("CONNECTED".equalsIgnoreCase(ci.state) || "TALKING".equalsIgnoreCase(ci.state)) {
                startTalkTimer(ci.call);
            }
        }

        // Update button text after loading initial calls
        updateHoldResumeButtonText();
    }

    private JButton createStyledButton(String text, Color color) {
        JButton button = new JButton(text);
        button.setBackground(color);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        button.setFont(new Font("SansSerif", Font.BOLD, 11));
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));

        button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent e) {
                button.setBackground(color.brighter());
            }
            public void mouseExited(java.awt.event.MouseEvent e) {
                button.setBackground(color);
            }
        });

        return button;
    }

    private CallRegistry.CallInfo selectedInfo() {
        int r = table.getSelectedRow();
        if (r >= 0 && r < model.getRowCount()) {
            CallRegistry.CallInfo ci = model.getAt(r);
            if (ci != null) {
                return ci;
            }
        }
        // No valid selection - return null, don't auto-select
        return null;
    }

    private void doPick() {
        CallRegistry.CallInfo ci = selectedInfo();
        if (ci == null) {
            statusLabel.setText("Please select a call to pick up");
            statusLabel.setForeground(Color.RED);
            return;
        }

        statusLabel.setText("Picking up call from " + (ci.number != null ? ci.number : "Unknown") + "...");
        statusLabel.setForeground(Color.BLUE);

        // Automatically put any existing active calls on hold before picking up new call
        List<CallRegistry.CallInfo> allCalls = CallRegistry.getInstance().snapshot();
        for (CallRegistry.CallInfo existingCall : allCalls) {
            if (existingCall.call != ci.call && ("CONNECTED".equalsIgnoreCase(existingCall.state) || "TALKING".equalsIgnoreCase(existingCall.state))) {
                // Put existing active call on hold
                stopTalkTimer(existingCall.call);
                startHoldTimer(existingCall.call);
                boolean holdOk = CallRegistry.getInstance().holdCall(existingCall.call);
                if (holdOk) {
                    CallRegistry.getInstance().addOrUpdate(existingCall.call, existingCall.number, "HOLD", existingCall.address);
                    System.out.println("AUTO-HOLD: Put existing call on hold before picking up new call");
                }
            }
        }

        // Now pick up the new call
        boolean ok = CallRegistry.getInstance().pickCall(ci.call);
        if (ok) {
            startTalkTimer(ci.call);
            statusLabel.setText("✓ Call picked up successfully");
            statusLabel.setForeground(new Color(40, 167, 69)); // Green
            System.out.println("PICK: Successfully picked up call from " + ci.number);
        } else {
            statusLabel.setText("✗ Failed to pick up call");
            statusLabel.setForeground(Color.RED);
            System.out.println("PICK: Failed to pick up call");
        }

        // Clear status after 3 seconds
        Timer timer = new Timer(3000, _ -> {
            SwingUtilities.invokeLater(() -> updateButtonStates());
        });
        timer.setRepeats(false);
        timer.start();
    }

    private void doHangup() {
        CallRegistry.CallInfo ci = selectedInfo();
        if (ci == null) {
            statusLabel.setText("Please select a call to hang up");
            statusLabel.setForeground(Color.RED);
            return;
        }

        statusLabel.setText("Hanging up call...");
        statusLabel.setForeground(Color.BLUE);

        stopTalkTimer(ci.call);
        boolean ok = CallRegistry.getInstance().disconnectCall(ci.call);
        if (ok) {
            CallRegistry.getInstance().remove(ci.call);
            statusLabel.setText("✓ Call hung up successfully");
            statusLabel.setForeground(new Color(40, 167, 69)); // Green
            System.out.println("HANGUP: Successfully hung up call");
        } else {
            statusLabel.setText("✗ Failed to hang up call");
            statusLabel.setForeground(Color.RED);
            System.out.println("HANGUP: Failed to hang up call");
        }

        // Clear status after 3 seconds
        Timer timer = new Timer(3000, _ -> {
            SwingUtilities.invokeLater(() -> updateButtonStates());
        });
        timer.setRepeats(false);
        timer.start();
    }

    private void doHoldResume() {
        CallRegistry.CallInfo ci = selectedInfo();
        if (ci == null) return;

        String state = ci.state != null ? ci.state.toLowerCase() : "";
        if (state.contains("hold") || state.contains("on hold")) {
            // Call is on hold, resume it
            doResume();
        } else if (state.contains("connected") || state.contains("talking")) {
            // Call is active, hold it
            doHold();
        } else {
            System.out.println("HOLD/RESUME: Cannot hold/resume call in state: " + ci.state);
        }
    }

    private void updateHoldResumeButtonText() {
        CallRegistry.CallInfo ci = selectedInfo();
        if (ci != null && ci.state != null) {
            String state = ci.state.toLowerCase();
            if (state.contains("hold") || state.contains("on hold")) {
                holdResumeBtn.setText("Resume");
            } else if (state.contains("connected") || state.contains("talking")) {
                holdResumeBtn.setText("Hold");
            } else {
                holdResumeBtn.setText("Hold/Resume");
            }
        } else {
            holdResumeBtn.setText("Hold/Resume");
        }
        updateButtonStates();
    }

    private void updateButtonStates() {
        CallRegistry.CallInfo ci = selectedInfo();
        boolean hasSelection = (ci != null);

        if (hasSelection && ci.state != null) {
            String state = ci.state.toLowerCase();
            String number = ci.number != null ? ci.number : "Unknown";
            String address = ci.address != null ? ci.address : "Unknown";

            // Update status label with call information
            statusLabel.setText("Selected: " + number + " → " + address + " (" + ci.state + ")");
            statusLabel.setForeground(Color.BLACK);

            // Pick button: enabled for ringing/alerting calls
            pickBtn.setEnabled(state.contains("alerting") || state.contains("created") || state.contains("ringing"));

            // Hold/Resume button: enabled for connected/talking or held calls
            holdResumeBtn.setEnabled(state.contains("connected") || state.contains("talking") ||
                                   state.contains("hold") || state.contains("on hold"));

            // Hangup button: enabled for most active calls
            hangupBtn.setEnabled(!state.contains("disconnected") && !state.contains("idle"));
        } else {
            // No selection - disable all buttons and show message
            statusLabel.setText("Select a call to perform actions");
            statusLabel.setForeground(Color.GRAY);

            pickBtn.setEnabled(false);
            holdResumeBtn.setEnabled(false);
            hangupBtn.setEnabled(false);
        }
    }

    private void doHold() {
        CallRegistry.CallInfo ci = selectedInfo();
        if (ci == null) {
            statusLabel.setText("Please select a call to hold");
            statusLabel.setForeground(Color.RED);
            return;
        }

        statusLabel.setText("Putting call on hold...");
        statusLabel.setForeground(Color.BLUE);

        stopTalkTimer(ci.call);
        startHoldTimer(ci.call);
        boolean ok = CallRegistry.getInstance().holdCall(ci.call);
        if (ok) {
            CallRegistry.getInstance().addOrUpdate(ci.call, ci.number, "HOLD", ci.address);
            statusLabel.setText("✓ Call put on hold");
            statusLabel.setForeground(new Color(255, 193, 7)); // Yellow/Orange
            System.out.println("HOLD: Successfully put call on hold");
        } else {
            statusLabel.setText("✗ Failed to put call on hold");
            statusLabel.setForeground(Color.RED);
            System.out.println("HOLD: Failed to put call on hold");
            // If hold failed, restart talk timer
            startTalkTimer(ci.call);
        }
        updateHoldResumeButtonText();

        // Clear status after 3 seconds
        Timer timer = new Timer(3000, _ -> {
            SwingUtilities.invokeLater(() -> updateButtonStates());
        });
        timer.setRepeats(false);
        timer.start();
    }

    private void doResume() {
        CallRegistry.CallInfo ci = selectedInfo();
        if (ci == null) {
            statusLabel.setText("Please select a call to resume");
            statusLabel.setForeground(Color.RED);
            System.out.println("RESUME UI: No call selected");
            return;
        }

        statusLabel.setText("Resuming call...");
        statusLabel.setForeground(Color.BLUE);

        System.out.println("RESUME UI: Selected call: " + ci.call + ", state: " + ci.state + ", wasHeld: " + ci.wasHeld);

        // 🔄 AUTO-HOLD LOGIC: Put any existing active calls on hold before resuming
        List<CallRegistry.CallInfo> allCalls = CallRegistry.getInstance().snapshot();
        for (CallRegistry.CallInfo existingCall : allCalls) {
            if (existingCall.call != ci.call &&
                ("CONNECTED".equalsIgnoreCase(existingCall.state) ||
                 "TALKING".equalsIgnoreCase(existingCall.state))) {

                // Put existing active call on hold
                stopTalkTimer(existingCall.call);
                startHoldTimer(existingCall.call);
                boolean holdOk = CallRegistry.getInstance().holdCall(existingCall.call);
                if (holdOk) {
                    CallRegistry.getInstance().addOrUpdate(existingCall.call, existingCall.number, "HOLD", existingCall.address);
                    System.out.println("AUTO-HOLD: Put existing call on hold before resuming selected call");
                }
            }
        }

        // Check if this is a Cisco held call that became invalid
        try {
            int callState = ci.call.getState();
            System.out.println("RESUME UI: Call state: " + callState + ", Call.INVALID: " + Call.INVALID + ", wasHeld: " + ci.wasHeld);
            if (callState == Call.INVALID && ci.wasHeld) {
                System.out.println("RESUME UI: Attempting to retrieve parked call first");
                // First try to resume the call (which will attempt park retrieval)
                boolean resumeSuccess = CallRegistry.getInstance().resumeCall(ci.call);
                if (resumeSuccess) {
                    stopHoldTimer(ci.call);
                    startTalkTimer(ci.call);
                    System.out.println("RESUME UI: Successfully resumed parked call");
                    return;
                } else {
                    System.out.println("RESUME UI: Park retrieval failed, trying direct dial");
                    // Only fall back to direct dial if park retrieval fails
                    String numberToDial = ci.originalNumber != null ? ci.originalNumber : ci.number;
                    if (numberToDial != null && !numberToDial.isEmpty()) {
                        boolean dialSuccess = JTAPIGui.makeOutboundCall(numberToDial);
                        if (dialSuccess) {
                            System.out.println("RESUME UI: Direct dial succeeded after park retrieval failed");
                            // Remove the invalid call from registry
                            CallRegistry.getInstance().remove(ci.call);
                            return;
                        } else {
                            System.out.println("RESUME UI: Direct dial also failed");
                            // Fall back to dialog if both methods fail
                            JOptionPane.showMessageDialog(this,
                                "Unable to resume call - both park retrieval and direct dial failed.\n" +
                                "Please resume the call in Jabber.",
                                "Resume Failed",
                                JOptionPane.WARNING_MESSAGE);
                            return;
                        }
                    } else {
                        System.out.println("RESUME UI: No number available to dial");
                        // Fall back to dialog if no number available
                        JOptionPane.showMessageDialog(this,
                            "Cisco held calls cannot be resumed directly.\n" +
                            "No phone number available to dial.\n" +
                            "Please resume the call in Jabber.",
                            "Resume Not Available",
                            JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("RESUME UI: Exception checking call state: " + e.getMessage());
            // Continue with resume attempt
        }

        // For non-invalid calls, try normal resume
        boolean ok = CallRegistry.getInstance().resumeCall(ci.call);
        if (ok) {
            stopHoldTimer(ci.call);
            startTalkTimer(ci.call);
            CallRegistry.getInstance().addOrUpdate(ci.call, ci.number, "CONNECTED", ci.address);
            statusLabel.setText("✓ Call resumed successfully");
            statusLabel.setForeground(new Color(40, 167, 69)); // Green
            System.out.println("RESUME UI: Successfully resumed call");
        } else {
            // If resume fails, try park retrieval methods first, then direct dial
            if (ci.wasHeld) {
                String numberToDial = ci.originalNumber != null ? ci.originalNumber : ci.number;
                if (numberToDial != null && !numberToDial.isEmpty()) {
                    System.out.println("RESUME UI: Standard resume failed, trying direct dial: " + numberToDial);
                    boolean dialSuccess = JTAPIGui.makeOutboundCall(numberToDial);
                    if (dialSuccess) {
                        System.out.println("RESUME UI: Direct dial succeeded after resume failure");
                        return;
                    }
                }
            }
            // Show informative message if all attempts fail
            if (ci.wasHeld) {
                statusLabel.setText("✗ Resume failed - Cisco held calls become invalid");
                statusLabel.setForeground(Color.RED);
                JOptionPane.showMessageDialog(this,
                    "Resume failed - Cisco held calls become invalid.\n" +
                    "Please resume the call in Jabber.",
                    "Resume Failed",
                    JOptionPane.WARNING_MESSAGE);
            }
        }
        updateHoldResumeButtonText();

        // Clear status after 5 seconds for resume operations
        Timer timer = new Timer(5000, _ -> {
            SwingUtilities.invokeLater(() -> updateButtonStates());
        });
        timer.setRepeats(false);
        timer.start();
    }

    private void startTalkTimer(Call call) {
        if (call == null) return;
        stopTalkTimer(call); // Stop any existing timer
        callStartTimes.put(call, System.currentTimeMillis());
        Timer timer = new Timer(1000, _ -> {
            // Only update the time columns instead of refreshing entire table
            SwingUtilities.invokeLater(() -> {
                int rowCount = model.getRowCount();
                for (int i = 0; i < rowCount; i++) {
                    CallRegistry.CallInfo ci = model.getAt(i);
                    if (ci != null && call.equals(ci.call)) {
                        model.fireTableRowsUpdated(i, i);
                        break;
                    }
                }
            });
        });
        talkTimers.put(call, timer);
        timer.start();
    }

    private void stopTalkTimer(Call call) {
        if (call == null) return;
        Timer timer = talkTimers.remove(call);
        if (timer != null) {
            timer.stop();
        }
        // Accumulate talk time before stopping
        Long startTime = callStartTimes.remove(call);
        if (startTime != null) {
            long talkDuration = System.currentTimeMillis() - startTime;
            totalTalkTime.put(call, totalTalkTime.getOrDefault(call, 0L) + talkDuration);
        }
    }

    private void startHoldTimer(Call call) {
        if (call == null) return;
        stopHoldTimer(call); // Stop any existing hold timer
        holdStartTimes.put(call, System.currentTimeMillis());
        Timer timer = new Timer(1000, _ -> {
            // Only update the time columns instead of refreshing entire table
            SwingUtilities.invokeLater(() -> {
                int rowCount = model.getRowCount();
                for (int i = 0; i < rowCount; i++) {
                    CallRegistry.CallInfo ci = model.getAt(i);
                    if (ci != null && call.equals(ci.call)) {
                        model.fireTableRowsUpdated(i, i);
                        break;
                    }
                }
            });
        });
        holdTimers.put(call, timer);
        timer.start();
    }

    private void stopHoldTimer(Call call) {
        if (call == null) return;
        Timer timer = holdTimers.remove(call);
        if (timer != null) {
            timer.stop();
        }
        // Accumulate hold time before stopping
        Long startTime = holdStartTimes.remove(call);
        if (startTime != null) {
            long holdDuration = System.currentTimeMillis() - startTime;
            totalHoldTime.put(call, totalHoldTime.getOrDefault(call, 0L) + holdDuration);
        }
    }

    private String formatTalkTime(Call call) {
        if (call == null) return "00:00";

        long talkTime = 0;

        // Show current talk time (only when actively talking)
        Long talkStart = callStartTimes.get(call);
        if (talkStart != null && !holdTimers.containsKey(call)) {
            talkTime = System.currentTimeMillis() - talkStart;
        }

        long seconds = talkTime / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;

        return String.format("%02d:%02d", minutes, seconds);
    }

    private String formatHoldTime(Call call) {
        if (call == null) return "00:00";

        long holdTime = 0;

        // Show current hold time (only when actively on hold)
        Long holdStart = holdStartTimes.get(call);
        if (holdStart != null) {
            holdTime = System.currentTimeMillis() - holdStart;
        }

        long seconds = holdTime / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;

        return String.format("%02d:%02d", minutes, seconds);
    }

    private String formatTotalTime(Call call) {
        if (call == null) return "00:00";

        long totalTime = 0;

        // Add accumulated talk time
        totalTime += totalTalkTime.getOrDefault(call, 0L);

        // Add accumulated hold time
        totalTime += totalHoldTime.getOrDefault(call, 0L);

        // Add current active time (talk or hold)
        if (holdTimers.containsKey(call)) {
            Long holdStart = holdStartTimes.get(call);
            if (holdStart != null) {
                totalTime += System.currentTimeMillis() - holdStart;
            }
        } else {
            Long talkStart = callStartTimes.get(call);
            if (talkStart != null) {
                totalTime += System.currentTimeMillis() - talkStart;
            }
        }

        long seconds = totalTime / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;

        return String.format("%02d:%02d", minutes, seconds);
    }

    @Override
    public void onCallAdded(CallRegistry.CallInfo info) {
        SwingUtilities.invokeLater(() -> {
            model.addOrUpdate(info);
            startBlink(info);
            if ("CONNECTED".equalsIgnoreCase(info.state) || "TALKING".equalsIgnoreCase(info.state)) {
                startTalkTimer(info.call);
            }
            updateHoldResumeButtonText();
        });
    }

    @Override
    public void onCallUpdated(CallRegistry.CallInfo info) {
        SwingUtilities.invokeLater(() -> {
            model.addOrUpdate(info);
            if ("CONNECTED".equalsIgnoreCase(info.state) || "TALKING".equalsIgnoreCase(info.state)) {
                if (!talkTimers.containsKey(info.call)) {
                    startTalkTimer(info.call);
                }
            } else {
                stopTalkTimer(info.call);
            }
            updateHoldResumeButtonText();
        });
    }

    @Override
    public void onCallRemoved(CallRegistry.CallInfo info) {
        SwingUtilities.invokeLater(() -> {
            stopBlink(info.call);
            stopTalkTimer(info.call);
            stopHoldTimer(info.call);
            // Clean up accumulated times
            totalTalkTime.remove(info.call);
            totalHoldTime.remove(info.call);
            model.remove(info);
            updateHoldResumeButtonText();
        });
    }

    private void startBlink(CallRegistry.CallInfo info) {
        if (info == null || info.call == null) return;
        stopBlink(info.call);
        final Call key = info.call;
        final int max = 6; // number of toggles (about 3s)
        final int[] count = {0};
        Timer t = new Timer(500, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                boolean on = !Boolean.TRUE.equals(blinkOn.get(key));
                blinkOn.put(key, on);
                table.repaint();
                count[0]++;
                if (count[0] >= max) {
                    stopBlink(key);
                }
            }
        });
        blinkTimers.put(info.call, t);
        t.setInitialDelay(0);
        t.start();
    }

    private void stopBlink(Call call) {
        if (call == null) return;
        Timer t = blinkTimers.remove(call);
        if (t != null) t.stop();
        blinkOn.remove(call);
        table.repaint();
    }

    private static class CallTableModel extends AbstractTableModel {
        private final java.util.List<CallRegistry.CallInfo> rows = new java.util.ArrayList<>();
        private final String[] cols = new String[]{"From", "To", "State", "Talk", "Hold", "Total"};
        private final CallListPanel panel;

        public CallTableModel(CallListPanel panel) {
            this.panel = panel;
        }

        public void addOrUpdate(CallRegistry.CallInfo ci) {
            int idx = indexOf(ci.call);
            if (idx >= 0) {
                rows.set(idx, ci);
                fireTableRowsUpdated(idx, idx);
            } else {
                rows.add(0, ci); // Add to top
                fireTableRowsInserted(0, 0);
            }
        }

        public void remove(CallRegistry.CallInfo ci) {
            int idx = indexOf(ci.call);
            if (idx >= 0) {
                rows.remove(idx);
                fireTableRowsDeleted(idx, idx);
            }
        }

        private int indexOf(Call call) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).call.equals(call)) return i;
            }
            return -1;
        }

        public CallRegistry.CallInfo getAt(int row) {
            return rows.get(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            CallRegistry.CallInfo ci = rows.get(r);

            switch(c) {
                case 0: return ci.number != null ? ci.number : ""; // From (calling number)
                case 1: return ci.address != null ? ci.address : ""; // To (called number/address)
                case 2: return ci.state != null ? ci.state : ""; // State
                case 3: return panel.formatTalkTime(ci.call); // Current talk time
                case 4: return panel.formatHoldTime(ci.call); // Current hold time
                case 5: return panel.formatTotalTime(ci.call); // Total call time
                default: return "";
            }
        }
    }
}
