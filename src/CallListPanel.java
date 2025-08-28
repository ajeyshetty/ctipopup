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
    // Button references
    private JButton holdResumeBtn;

    public CallListPanel() {
        super(new BorderLayout());
        model = new CallTableModel();
        table = new JTable(model);

        // Configure table appearance
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(25);
        table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
        table.setFont(new Font("SansSerif", Font.PLAIN, 11));
        table.setGridColor(Color.LIGHT_GRAY);
        table.setShowGrid(true);

        // Set column widths
        table.getColumnModel().getColumn(0).setPreferredWidth(120); // From
        table.getColumnModel().getColumn(1).setPreferredWidth(120); // To
        table.getColumnModel().getColumn(2).setPreferredWidth(80);  // State
        table.getColumnModel().getColumn(3).setPreferredWidth(60);  // Time

        // Create control panel with better layout
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        controlPanel.setBackground(new Color(240, 240, 240));
        controlPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JButton pickBtn = createStyledButton("Pick", new Color(40, 167, 69));
        holdResumeBtn = createStyledButton("Hold/Resume", new Color(255, 193, 7));
        JButton hangupBtn = createStyledButton("Hangup", new Color(220, 53, 69));

        pickBtn.addActionListener(_ -> doPick());
        holdResumeBtn.addActionListener(_ -> doHoldResume());
        hangupBtn.addActionListener(_ -> doHangup());

        controlPanel.add(pickBtn);
        controlPanel.add(holdResumeBtn);
        controlPanel.add(hangupBtn);

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
        if (r >= 0) return model.getAt(r);
        // Fallback to newest connected/talking call
        for (int i = 0; i < model.getRowCount(); i++) {
            CallRegistry.CallInfo ci = model.getAt(i);
            if (ci != null && ("CONNECTED".equalsIgnoreCase(ci.state) || "TALKING".equalsIgnoreCase(ci.state))) {
                table.setRowSelectionInterval(i, i);
                return ci;
            }
        }
        // If no connected call, return first call
        if (model.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
            return model.getAt(0);
        }
        return null;
    }

    private void doPick() {
        CallRegistry.CallInfo ci = selectedInfo();
        if (ci == null) return;

        boolean ok = CallRegistry.getInstance().pickCall(ci.call);
        if (ok) {
            startTalkTimer(ci.call);
            System.out.println("PICK: Successfully picked up call from " + ci.number);
        } else {
            System.out.println("PICK: Failed to pick up call");
        }
    }

    private void doHangup() {
        CallRegistry.CallInfo ci = selectedInfo();
        if (ci == null) return;

        stopTalkTimer(ci.call);
        boolean ok = CallRegistry.getInstance().disconnectCall(ci.call);
        if (ok) {
            CallRegistry.getInstance().remove(ci.call);
            System.out.println("HANGUP: Successfully hung up call");
        } else {
            System.out.println("HANGUP: Failed to hang up call");
        }
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
    }

    private void doHold() {
        CallRegistry.CallInfo ci = selectedInfo();
        if (ci == null) return;

        stopTalkTimer(ci.call);
        boolean ok = CallRegistry.getInstance().holdCall(ci.call);
        if (ok) {
            CallRegistry.getInstance().addOrUpdate(ci.call, ci.number, "HOLD", ci.address);
            System.out.println("HOLD: Successfully put call on hold");
        } else {
            System.out.println("HOLD: Failed to put call on hold");
        }
        updateHoldResumeButtonText();
    }

    private void doResume() {
        CallRegistry.CallInfo ci = selectedInfo();
        if (ci == null) {
            System.out.println("RESUME UI: No call selected");
            return;
        }

        System.out.println("RESUME UI: Selected call: " + ci.call + ", state: " + ci.state + ", wasHeld: " + ci.wasHeld);

        // Check if this is a Cisco held call that became invalid
        try {
            int callState = ci.call.getState();
            System.out.println("RESUME UI: Call state: " + callState + ", Call.INVALID: " + Call.INVALID + ", wasHeld: " + ci.wasHeld);
            if (callState == Call.INVALID && ci.wasHeld) {
                System.out.println("RESUME UI: Attempting to retrieve parked call first");
                // First try to resume the call (which will attempt park retrieval)
                boolean resumeSuccess = CallRegistry.getInstance().resumeCall(ci.call);
                if (resumeSuccess) {
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
            startTalkTimer(ci.call);
            CallRegistry.getInstance().addOrUpdate(ci.call, ci.number, "CONNECTED", ci.address);
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
                JOptionPane.showMessageDialog(this,
                    "Resume failed - Cisco held calls become invalid.\n" +
                    "Please resume the call in Jabber.",
                    "Resume Failed",
                    JOptionPane.WARNING_MESSAGE);
            }
        }
        updateHoldResumeButtonText();
    }

    private void startTalkTimer(Call call) {
        if (call == null) return;
        stopTalkTimer(call); // Stop any existing timer
        callStartTimes.put(call, System.currentTimeMillis());
        Timer timer = new Timer(1000, _ -> {
            model.fireTableDataChanged(); // Refresh display every second
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
        callStartTimes.remove(call);
    }

    private String formatTalkTime(Call call) {
        Long startTime = callStartTimes.get(call);
        if (startTime == null) return "00:00";

        long elapsed = System.currentTimeMillis() - startTime;
        long seconds = elapsed / 1000;
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
        private final String[] cols = new String[]{"From", "To", "State", "Time"};

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
            // Get the panel reference from the table
            JTable table = (JTable) SwingUtilities.getAncestorOfClass(JTable.class, null);
            CallListPanel panel = null;
            if (table != null) {
                panel = (CallListPanel) SwingUtilities.getAncestorOfClass(CallListPanel.class, table);
            }

            switch(c) {
                case 0: return ci.number != null ? ci.number : ""; // From (calling number)
                case 1: return ci.address != null ? ci.address : ""; // To (called number/address)
                case 2: return ci.state != null ? ci.state : ""; // State
                case 3: return panel != null ? panel.formatTalkTime(ci.call) : "00:00"; // Talk time
                default: return "";
            }
        }
    }
}
