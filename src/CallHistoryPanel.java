import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.ArrayList;

/**
 * Call History Panel for displaying and managing call history
 */
public class CallHistoryPanel extends JPanel {
    private JTable historyTable;
    private HistoryTableModel tableModel;
    private JButton refreshBtn;
    private JButton clearBtn;
    private JButton callBackBtn;
    private JComboBox<String> filterCombo;
    private JTextField searchField;

    public CallHistoryPanel() {
        initComponents();
        loadHistory();
    }

    private void initComponents() {
        setLayout(new BorderLayout());
        setBackground(Color.WHITE);

        // Header panel
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(new Color(245, 248, 250));
        headerPanel.setBorder(BorderFactory.createEmptyBorder(15, 20, 15, 20));

        JLabel titleLabel = new JLabel("Call History");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        titleLabel.setForeground(new Color(33, 37, 41));
        headerPanel.add(titleLabel, BorderLayout.WEST);

        // Control panel
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        controlPanel.setBackground(new Color(245, 248, 250));

        // Filter combo
        filterCombo = new JComboBox<>(new String[]{"All Calls", "Today's Calls", "Missed Calls", "Inbound", "Outbound"});
        filterCombo.addActionListener(_ -> filterHistory());
        controlPanel.add(new JLabel("Filter:"));
        controlPanel.add(filterCombo);

        // Search field
        searchField = new JTextField(15);
        searchField.setToolTipText("Search by number or name");
        searchField.addActionListener(_ -> filterHistory());
        controlPanel.add(new JLabel("Search:"));
        controlPanel.add(searchField);

        headerPanel.add(controlPanel, BorderLayout.EAST);
        add(headerPanel, BorderLayout.NORTH);

        // Table
        tableModel = new HistoryTableModel();
        historyTable = new JTable(tableModel);
        historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        historyTable.setRowHeight(30);
        historyTable.setGridColor(new Color(222, 226, 230));
        historyTable.setShowGrid(true);

        // Set column widths
        TableColumnModel columnModel = historyTable.getColumnModel();
        columnModel.getColumn(0).setPreferredWidth(60);  // Type
        columnModel.getColumn(1).setPreferredWidth(120); // Time
        columnModel.getColumn(2).setPreferredWidth(120); // Number
        columnModel.getColumn(3).setPreferredWidth(150); // Name
        columnModel.getColumn(4).setPreferredWidth(80);  // Duration
        columnModel.getColumn(5).setPreferredWidth(100); // Status

        // Custom renderer for call type
        historyTable.getColumnModel().getColumn(0).setCellRenderer(new CallTypeRenderer());

        JScrollPane scrollPane = new JScrollPane(historyTable);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
        add(scrollPane, BorderLayout.CENTER);

        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 10));
        buttonPanel.setBackground(new Color(245, 248, 250));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(10, 20, 15, 20));

        refreshBtn = createStyledButton("Refresh", new Color(0, 123, 255));
        clearBtn = createStyledButton("Clear History", new Color(220, 53, 69));
        callBackBtn = createStyledButton("Call Back", new Color(40, 167, 69));

        refreshBtn.addActionListener(_ -> loadHistory());
        clearBtn.addActionListener(_ -> clearHistory());
        callBackBtn.addActionListener(_ -> callBack());

        buttonPanel.add(refreshBtn);
        buttonPanel.add(callBackBtn);
        buttonPanel.add(clearBtn);

        add(buttonPanel, BorderLayout.SOUTH);

        // Double-click to call back
        historyTable.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    callBack();
                }
            }
        });
    }

    private JButton createStyledButton(String text, Color color) {
        JButton button = new JButton(text);
        button.setBackground(color);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));

        button.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                button.setBackground(color.brighter());
            }
            public void mouseExited(MouseEvent e) {
                button.setBackground(color);
            }
        });

        return button;
    }

    private void loadHistory() {
        List<CallHistory.CallRecord> records = CallHistory.getInstance().getAllCalls();
        tableModel.setData(records);
        updateButtonStates();
    }

    private void filterHistory() {
        String filter = (String) filterCombo.getSelectedItem();
        String search = searchField.getText().trim().toLowerCase();

        List<CallHistory.CallRecord> allRecords = CallHistory.getInstance().getAllCalls();
        List<CallHistory.CallRecord> filtered = new ArrayList<>();

        for (CallHistory.CallRecord record : allRecords) {
            // Apply filter
            boolean matchesFilter = false;
            switch (filter) {
                case "All Calls":
                    matchesFilter = true;
                    break;
                case "Today's Calls":
                    matchesFilter = record.isToday();
                    break;
                case "Missed Calls":
                    matchesFilter = "MISSED".equals(record.direction) ||
                                   ("INBOUND".equals(record.direction) && !record.answered);
                    break;
                case "Inbound":
                    matchesFilter = "INBOUND".equals(record.direction);
                    break;
                case "Outbound":
                    matchesFilter = "OUTBOUND".equals(record.direction);
                    break;
            }

            // Apply search
            boolean matchesSearch = search.isEmpty() ||
                record.number.toLowerCase().contains(search) ||
                record.name.toLowerCase().contains(search);

            if (matchesFilter && matchesSearch) {
                filtered.add(record);
            }
        }

        tableModel.setData(filtered);
        updateButtonStates();
    }

    private void clearHistory() {
        int result = JOptionPane.showConfirmDialog(
            this,
            "Are you sure you want to clear all call history?",
            "Clear History",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );

        if (result == JOptionPane.YES_OPTION) {
            CallHistory.getInstance().clearHistory();
            loadHistory();
        }
    }

    private void callBack() {
        int selectedRow = historyTable.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this, "Please select a call to call back.",
                                        "No Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }

        CallHistory.CallRecord record = tableModel.getRecordAt(selectedRow);
        if (record == null || record.number == null || record.number.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No phone number available for this call.",
                                        "Invalid Number", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Use JTAPIGui to make the call
        try {
            boolean success = JTAPIGui.makeOutboundCall(record.number);
            if (success) {
                JOptionPane.showMessageDialog(this, "Calling " + record.number + "...",
                                            "Call Initiated", JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this, "Failed to initiate call to " + record.number,
                                            "Call Failed", JOptionPane.ERROR_MESSAGE);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error calling " + record.number + ": " + e.getMessage(),
                                        "Call Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateButtonStates() {
        int selectedRow = historyTable.getSelectedRow();
        boolean hasSelection = selectedRow >= 0;

        callBackBtn.setEnabled(hasSelection && tableModel.getRowCount() > 0);
        clearBtn.setEnabled(tableModel.getRowCount() > 0);
    }

    // Custom renderer for call type icons
    private static class CallTypeRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            String type = (String) value;
            setText("");

            if (type != null) {
                if ("INBOUND".equals(type)) {
                    setIcon(createIcon("↓", new Color(40, 167, 69)));
                } else if ("OUTBOUND".equals(type)) {
                    setIcon(createIcon("↑", new Color(0, 123, 255)));
                } else if ("MISSED".equals(type)) {
                    setIcon(createIcon("✗", new Color(220, 53, 69)));
                } else {
                    setIcon(null);
                }
            } else {
                setIcon(null);
            }

            return this;
        }

        private Icon createIcon(String symbol, Color color) {
            return new Icon() {
                @Override
                public void paintIcon(Component c, Graphics g, int x, int y) {
                    Graphics2D g2d = (Graphics2D) g.create();
                    g2d.setColor(color);
                    g2d.setFont(new Font("Segoe UI", Font.BOLD, 14));
                    FontMetrics fm = g2d.getFontMetrics();
                    int symbolWidth = fm.stringWidth(symbol);
                    int symbolHeight = fm.getHeight();
                    g2d.drawString(symbol, x + (16 - symbolWidth) / 2, y + (16 + symbolHeight) / 2 - 2);
                    g2d.dispose();
                }

                @Override
                public int getIconWidth() { return 16; }

                @Override
                public int getIconHeight() { return 16; }
            };
        }
    }

    // Table model for call history
    private static class HistoryTableModel extends AbstractTableModel {
        private final String[] columns = {"Type", "Time", "Number", "Name", "Duration", "Status"};
        private List<CallHistory.CallRecord> records = new ArrayList<>();

        public void setData(List<CallHistory.CallRecord> records) {
            this.records = new ArrayList<>(records);
            fireTableDataChanged();
        }

        public CallHistory.CallRecord getRecordAt(int row) {
            if (row >= 0 && row < records.size()) {
                return records.get(row);
            }
            return null;
        }

        @Override
        public int getRowCount() {
            return records.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            CallHistory.CallRecord record = records.get(rowIndex);

            switch (columnIndex) {
                case 0: return record != null ? record.direction : "UNKNOWN";
                case 1: return record != null ? record.getFormattedStartTime() : "";
                case 2: return record != null ? record.number : "";
                case 3: return record != null ? record.name : "";
                case 4: return record != null ? record.getFormattedDuration() : "";
                case 5: return getStatusText(record);
                default: return "";
            }
        }

        private String getStatusText(CallHistory.CallRecord record) {
            if (record == null || record.direction == null) {
                return "Unknown";
            }
            if ("MISSED".equals(record.direction)) {
                return "Missed";
            } else if ("INBOUND".equals(record.direction)) {
                return record.answered ? "Answered" : "Missed";
            } else if ("OUTBOUND".equals(record.direction)) {
                return record.answered ? "Completed" : "Failed";
            }
            return "Unknown";
        }
    }
}
