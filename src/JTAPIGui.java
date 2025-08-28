import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import javax.telephony.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Properties;

public class JTAPIGui {
    private JFrame frame;
    private JTextField userField;
    private JPasswordField passField;
    private JTextField phoneField;
    private JTextField cucmHostField;
    private JTextField urlField;
    private JComboBox<String> triggerCombo;
    private JCheckBox rememberMeCheck;
    private JCheckBox enablePopCheck;
    private JButton startBtn;
    private JButton stopBtn;
    private JLabel statusLabel;
    private JTabbedPane tabbedPane;

    private Provider provider;
    private Thread workerThread;
    private JTAPICallerInfo listener;

    // Control whether screen-pop URL opening is enabled. Default true.
    private static volatile boolean urlPopEnabled = true;
    public static boolean isUrlPopEnabled() { return urlPopEnabled; }

    private static final String CONFIG_DIR = System.getProperty("user.home") + "/.jtapi_config";
    private static final String CONFIG_FILE = CONFIG_DIR + "/config.properties";

    public static void main(String[] args) {
        // Try to bind the single-instance port. If bind succeeds we are primary.
        try {
            primaryServerSocket = new ServerSocket(SINGLE_INSTANCE_PORT, 0, InetAddress.getByName("127.0.0.1"));
            primaryServerSocket.setReuseAddress(true);
            // Start server thread to accept SHOW requests; it will reference INSTANCE after UI starts
            startSingleInstanceServer(primaryServerSocket);
        } catch (IOException bindEx) {
            // Could not bind - assume another instance is running. Signal it and exit.
            try {
                if (sendShowRequest()) return;
            } catch (Exception ignored) {}
            // If signaling failed, continue to start UI as a fallback
        }

        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> {
            INSTANCE = new JTAPIGui();
            INSTANCE.buildAndShow();
        });
    }

    // Port used for single-instance communication on localhost. Pick a port unlikely to conflict.
    private static final int SINGLE_INSTANCE_PORT = 45678;

    // Server socket for the primary instance (if we successfully bind)
    private static ServerSocket primaryServerSocket = null;

    // Reference to the GUI instance so the server thread can bring it to front
    private static volatile JTAPIGui INSTANCE = null;

    // Try to connect to an existing instance and send a SHOW command. Returns true if succeeded.
    private static boolean sendShowRequest() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", SINGLE_INSTANCE_PORT), 250);
            try (OutputStream os = s.getOutputStream();
                 Writer w = new OutputStreamWriter(os, "UTF-8")) {
                w.write("SHOW\n");
                w.flush();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void buildAndShow() {
        frame = new JFrame("LKQ CTI Popup");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.getContentPane().setBackground(new Color(34, 34, 34)); // Dark gray background

        // Set custom icon for the application
        try {
            // Try to load the icon from the classpath (when running from JAR)
            java.net.URL iconURL = getClass().getClassLoader().getResource("icon.png");
            if (iconURL != null) {
                ImageIcon icon = new ImageIcon(iconURL);
                frame.setIconImage(icon.getImage());
            } else {
                // Fallback: try to load from file system (when running from IDE)
                try {
                    ImageIcon icon = new ImageIcon("src/icon.png");
                    if (icon.getImageLoadStatus() == MediaTracker.COMPLETE) {
                        frame.setIconImage(icon.getImage());
                    }
                } catch (Exception e) {
                    System.out.println("Could not load icon from file system: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.out.println("Could not set custom icon: " + e.getMessage());
        }

        // Initialize all fields first
        userField = createTextField("Enter Username", "");
        passField = new JPasswordField();
        passField.setBackground(Color.WHITE);
        passField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(206, 212, 218), 1, true),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)
        ));
        passField.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        phoneField = createTextField("Enter Phone#", "");
        cucmHostField = createTextField("Enter CUCM Host", "ccmaur1vpub.lkqvoice.com");
        urlField = createTextField("Enter URL Template (use {number})", "https://salesassistant.lkqcorp.com/customers/{number}");
        triggerCombo = new JComboBox<>(new String[]{"RINGING", "CONNECTED"});
        triggerCombo.setSelectedItem("CONNECTED");
        triggerCombo.setBackground(Color.WHITE);
        triggerCombo.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(206, 212, 218), 1, true),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)
        ));
        triggerCombo.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        rememberMeCheck = new JCheckBox("Remember Me");
        rememberMeCheck.setBackground(Color.WHITE);
        rememberMeCheck.setFont(new Font("SansSerif", Font.PLAIN, 14));

        // Load saved settings after initializing fields
        loadSavedSettings();

    // left sidebar removed per user request

        // Tabbed pane for content
        tabbedPane = new JTabbedPane();
        tabbedPane.setBackground(new Color(245, 248, 250)); // Clean light background
        tabbedPane.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        tabbedPane.setFont(new Font("Segoe UI", Font.BOLD, 13));

        // Connection tab
        JPanel connPanel = createContentPanel();
        connPanel.setLayout(new BorderLayout());

        // Header section
        JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        headerPanel.setBackground(Color.WHITE);
        headerPanel.setBorder(BorderFactory.createEmptyBorder(10, 20, 15, 20));
        JLabel headerLabel = new JLabel("Connection Settings");
        headerLabel.setFont(new Font("Segoe UI", Font.BOLD, 16));
        headerLabel.setForeground(new Color(33, 37, 41));
        headerPanel.add(headerLabel);
        connPanel.add(headerPanel, BorderLayout.NORTH);

        // Form section
        JPanel formPanel = createFormPanel();
        connPanel.add(formPanel, BorderLayout.CENTER);
        tabbedPane.addTab("Connection", connPanel);

        // Call Settings tab
        JPanel callPanel = createContentPanel();
        callPanel.setLayout(new BorderLayout());

        // Header section
        JPanel callHeaderPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        callHeaderPanel.setBackground(Color.WHITE);
        callHeaderPanel.setBorder(BorderFactory.createEmptyBorder(10, 20, 15, 20));
        JLabel callHeaderLabel = new JLabel("Call Settings");
        callHeaderLabel.setFont(new Font("Segoe UI", Font.BOLD, 16));
        callHeaderLabel.setForeground(new Color(33, 37, 41));
        callHeaderPanel.add(callHeaderLabel);
        callPanel.add(callHeaderPanel, BorderLayout.NORTH);

        // Form section
        JPanel callFormPanel = createCallFormPanel();
        callPanel.add(callFormPanel, BorderLayout.CENTER);
        tabbedPane.addTab("Call Settings", callPanel);

            // Active Calls tab
            try {
                CallListPanel callsPanel = new CallListPanel();
                tabbedPane.addTab("Calls", callsPanel);
            } catch (Throwable t) {
                System.out.println("Failed to create Calls panel: " + t.getMessage());
            }

        frame.add(tabbedPane, BorderLayout.CENTER);

        // Control panel (centered buttons)
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10));
        controlPanel.setBackground(new Color(232, 236, 239));
        controlPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        startBtn = createActionButton("Start", "▶", new Color(0, 123, 255));
        startBtn.setPreferredSize(new Dimension(120, 50));
        stopBtn = createActionButton("Stop", "■", new Color(220, 53, 69));
        stopBtn.setPreferredSize(new Dimension(120, 50));
        stopBtn.setEnabled(false);
        controlPanel.add(startBtn);
        controlPanel.add(stopBtn);
        frame.add(controlPanel, BorderLayout.NORTH);

        // Status panel
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusPanel.setBackground(new Color(232, 236, 239));
        statusPanel.setBorder(BorderFactory.createEmptyBorder(5, 15, 5, 15));
        statusLabel = new JLabel("Disconnected");
        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        statusLabel.setForeground(new Color(220, 53, 69)); // Red for Disconnected
        statusPanel.add(statusLabel);
        frame.add(statusPanel, BorderLayout.SOUTH);

        // Action listeners
        startBtn.addActionListener(e -> {
            System.out.println("Start clicked");
            startListener();
        });
        stopBtn.addActionListener(e -> {
            System.out.println("Stop clicked");
            stopListener();
        });

        // Input validation listener
        ActionListener validateInputs = e -> {
            String user = userField.getText().trim();
            String host = cucmHostField.getText().trim();
            startBtn.setEnabled(!user.isEmpty() && !host.isEmpty());
        };
        userField.addActionListener(validateInputs);
        cucmHostField.addActionListener(validateInputs);
        passField.addActionListener(validateInputs);

        frame.pack();
        frame.setMinimumSize(new Dimension(800, 600));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    // start background cleaner to remove unwanted CiscoJtapi*.log files
    startLogCleaner();

    }

    private void loadSavedSettings() {
        Properties props = new Properties();
        File configFile = new File(CONFIG_FILE);
        if (configFile.exists()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                props.load(fis);
                if (userField != null) userField.setText(props.getProperty("username", ""));
                if (passField != null) passField.setText(props.getProperty("password", ""));
                if (cucmHostField != null) cucmHostField.setText(props.getProperty("cucmHost", ""));
                if (phoneField != null) phoneField.setText(props.getProperty("phone", ""));
                if (props.containsKey("username") || props.containsKey("password") || props.containsKey("cucmHost") || props.containsKey("phone")) {
                    rememberMeCheck.setSelected(true);
                }
            } catch (IOException e) {
                updateStatus("Disconnected: Failed to load settings - " + e.getMessage(), new Color(220, 53, 69));
            }
        }
    }

    private void saveSettings() {
        if (!rememberMeCheck.isSelected()) return;
        Properties props = new Properties();
        if (userField != null) props.setProperty("username", userField.getText().trim());
        if (passField != null) props.setProperty("password", new String(passField.getPassword()));
        if (cucmHostField != null) props.setProperty("cucmHost", cucmHostField.getText().trim());
    if (phoneField != null) props.setProperty("phone", phoneField.getText().trim());

        File configDir = new File(CONFIG_DIR);
        if (!configDir.exists()) {
            configDir.mkdirs();
        }

        try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
            props.store(fos, "JTAPI GUI Settings");
        } catch (IOException e) {
            updateStatus("Disconnected: Failed to save settings - " + e.getMessage(), new Color(220, 53, 69));
        }
    }

    private JButton createSidebarButton(String text, String icon) {
        JButton button = new JButton(icon + " " + text);
        button.setBackground(new Color(51, 51, 51));
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 45));
        button.setFont(new Font("SansSerif", Font.BOLD, 14));
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setBackground(new Color(80, 80, 80));
            }
            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(new Color(51, 51, 51));
            }
        });
        return button;
    }

    private JButton createActionButton(String text, String icon, Color color) {
        JButton button = new JButton(icon + " " + text);
        button.setBackground(color);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
        button.setFont(new Font("SansSerif", Font.BOLD, 16));
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setBackground(color.brighter());
            }
            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(color);
            }
        });
        return button;
    }

    private JPanel createContentPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(200, 200, 200), 1, true),
            BorderFactory.createEmptyBorder(15, 15, 15, 15)
        ));
        return panel;
    }

    private JPanel createFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(Color.WHITE);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(12, 20, 12, 20);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        // Username field
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        JLabel userLabel = new JLabel("Username:");
        userLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        userLabel.setForeground(new Color(73, 80, 87));
        panel.add(userLabel, gbc);

        gbc.gridx = 1; gbc.weightx = 1;
        panel.add(userField, gbc);

        // Password field
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        JLabel passLabel = new JLabel("Password:");
        passLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        passLabel.setForeground(new Color(73, 80, 87));
        panel.add(passLabel, gbc);

        gbc.gridx = 1; gbc.weightx = 1;
        panel.add(passField, gbc);

        // Phone field
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        JLabel phoneLabel = new JLabel("Phone Number:");
        phoneLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        phoneLabel.setForeground(new Color(73, 80, 87));
        panel.add(phoneLabel, gbc);

        gbc.gridx = 1; gbc.weightx = 1;
        panel.add(phoneField, gbc);

        // Remember me checkbox
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2; gbc.weightx = 1;
        rememberMeCheck.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        rememberMeCheck.setForeground(new Color(73, 80, 87));
        panel.add(rememberMeCheck, gbc);

        return panel;
    }

    private JPanel createCallFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(Color.WHITE);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(12, 20, 12, 20);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        // CUCM Host field
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        JLabel hostLabel = new JLabel("CUCM Host:");
        hostLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        hostLabel.setForeground(new Color(73, 80, 87));
        panel.add(hostLabel, gbc);

        gbc.gridx = 1; gbc.weightx = 1;
        panel.add(cucmHostField, gbc);

        // URL Template field
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        JLabel urlLabel = new JLabel("URL Template:");
        urlLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        urlLabel.setForeground(new Color(73, 80, 87));
        panel.add(urlLabel, gbc);

        gbc.gridx = 1; gbc.weightx = 1;
        panel.add(urlField, gbc);

        // Trigger Event field
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        JLabel triggerLabel = new JLabel("Trigger Event:");
        triggerLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        triggerLabel.setForeground(new Color(73, 80, 87));
        panel.add(triggerLabel, gbc);

        gbc.gridx = 1; gbc.weightx = 1;
        panel.add(triggerCombo, gbc);

        // Enable Screen Pop checkbox
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2; gbc.weightx = 1;
        enablePopCheck = new JCheckBox("Enable Screen Pop", true);
        enablePopCheck.setBackground(Color.WHITE);
        enablePopCheck.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        enablePopCheck.setForeground(new Color(73, 80, 87));
        enablePopCheck.addActionListener(e -> {
            urlPopEnabled = enablePopCheck.isSelected();
            System.out.println("Screen pop enabled=" + urlPopEnabled);
        });
        panel.add(enablePopCheck, gbc);

        return panel;
    }

    private JPanel createSectionPanel(String title) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(150, 150, 150), 1, true),
            title,
            javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
            javax.swing.border.TitledBorder.DEFAULT_POSITION,
            new Font("SansSerif", Font.BOLD, 16)
        ));
        return panel;
    }

    private JTextField createTextField(String placeholder, String defaultText) {
        JTextField field = new JTextField(defaultText);
        field.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(206, 212, 218), 1, true),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)
        ));
        field.setBackground(Color.WHITE);
        field.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        field.setToolTipText(placeholder);
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                field.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(0, 123, 255), 2, true),
                    BorderFactory.createEmptyBorder(7, 11, 7, 11)
                ));
            }
            @Override
            public void focusLost(FocusEvent e) {
                if (field.getText().trim().isEmpty() && (field == userField || field == cucmHostField)) {
                    field.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(220, 53, 69), 2, true),
                        BorderFactory.createEmptyBorder(7, 11, 7, 11)
                    ));
                } else {
                    field.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(206, 212, 218), 1, true),
                        BorderFactory.createEmptyBorder(8, 12, 8, 12)
                    ));
                }
            }
        });
        return field;
    }

    // ...existing code... (addFieldToPanel already defined above)

    private void updateStatus(String status, Color color) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(status);
            statusLabel.setForeground(color);
        });
    }

    private void startListener() {
        String user = userField.getText().trim();
        String pass = new String(passField.getPassword());
        String phone = phoneField.getText().trim();
        String host = cucmHostField.getText().trim();
        String urlTemplate = urlField.getText().trim();

        if (host.isEmpty() || user.isEmpty()) {
            updateStatus("Disconnected: CUCM host and username are required", new Color(220, 53, 69));
            return;
        }

        saveSettings(); // Save settings if "Remember Me" is checked

        updateStatus("Trying to Connect to " + host, new Color(255, 193, 7)); // Yellow

        String providerString = host + ";login=" + user + ";passwd=" + pass;

        final String providerStringFinal = providerString;
        final String phoneFinal = phone;
        final String urlTemplateFinal = urlTemplate;
        final String triggerFinal = (triggerCombo.getSelectedItem() != null ? triggerCombo.getSelectedItem().toString() : "CONNECTED");

        // Disable only the start button to prevent multiple clicks
        startBtn.setEnabled(false);

        workerThread = new Thread(() -> {
            boolean connectionSuccessful = false;
            boolean subscriptionSuccessful = false;
            
            try {
                JtapiPeer peer = JtapiPeerFactory.getJtapiPeer(null);
                provider = peer.getProvider(providerStringFinal);
                connectionSuccessful = true;
                
                if ("ALL".equalsIgnoreCase(phoneFinal)) {
                    JTAPICallerInfo allListener = new JTAPICallerInfo(urlTemplateFinal, triggerFinal, null);
                    Address[] all = provider.getAddresses();
                    for (Address a : all) {
                        try {
                            a.addCallObserver(allListener);
                        } catch (Exception ex) {
                            updateStatus("Connected: Subscribe failed for some addresses - " + ex.getMessage(), new Color(40, 167, 69));
                        }
                    }
                    subscriptionSuccessful = true;
                    updateStatus("Connected: Subscribed to ALL (" + (all != null ? all.length : 0) + " addresses)", new Color(40, 167, 69));
                } else {
                    try {
                        Address a = provider.getAddress(phoneFinal);
                        JTAPICallerInfo addrListener = new JTAPICallerInfo(urlTemplateFinal, triggerFinal, a.getName());
                        a.addCallObserver(addrListener);
                        subscriptionSuccessful = true;
                        updateStatus("Connected: Subscribed to " + a.getName(), new Color(40, 167, 69));
                    } catch (Exception ex) {
                        updateStatus("Disconnected: Failed to subscribe to '" + phoneFinal + "' - " + ex.getMessage(), new Color(220, 53, 69));
                        try {
                            Address[] available = provider.getAddresses();
                            boolean foundFuzzy = false;
                            for (Address av : (available != null ? available : new Address[0])) {
                                try {
                                    if (av.getName().contains(phoneFinal)) {
                                        JTAPICallerInfo fuzzyListener = new JTAPICallerInfo(urlTemplateFinal, triggerFinal, av.getName());
                                        av.addCallObserver(fuzzyListener);
                                        subscriptionSuccessful = true;
                                        foundFuzzy = true;
                                        updateStatus("Connected: Subscribed fuzzy to " + av.getName(), new Color(40, 167, 69));
                                        break; // Exit after first successful fuzzy match
                                    }
                                } catch (Exception e) {
                                    updateStatus("Disconnected: Fuzzy subscribe failed for " + av.getName() + " - " + e.getMessage(), new Color(220, 53, 69));
                                }
                            }
                            if (!foundFuzzy) {
                                updateStatus("Disconnected: No matching phone number found", new Color(220, 53, 69));
                            }
                        } catch (Exception e2) {
                            updateStatus("Disconnected: Failed to list addresses - " + e2.getMessage(), new Color(220, 53, 69));
                        }
                    }
                }
                
                // Only disable fields and enable stop button if both connection and subscription were successful
                if (connectionSuccessful && subscriptionSuccessful) {
                    SwingUtilities.invokeLater(() -> {
                        userField.setEnabled(false);
                        passField.setEnabled(false);
                        phoneField.setEnabled(false);
                        cucmHostField.setEnabled(false);
                        urlField.setEnabled(false);
                        triggerCombo.setEnabled(false);
                        rememberMeCheck.setEnabled(false);
                        startBtn.setEnabled(false);
                        stopBtn.setEnabled(true);
                    });
                } else {
                    // Re-enable start button if connection or subscription failed
                    SwingUtilities.invokeLater(() -> {
                        startBtn.setEnabled(true);
                    });
                    // Clean up provider if subscription failed
                    if (connectionSuccessful && !subscriptionSuccessful && provider != null) {
                        try {
                            provider.shutdown();
                        } catch (Exception e) {
                            // Ignore shutdown errors
                        }
                        provider = null;
                    }
                }
            } catch (Exception ex) {
                updateStatus("Disconnected: Failed to start listener - " + ex.getMessage(), new Color(220, 53, 69));
                SwingUtilities.invokeLater(() -> {
                    startBtn.setEnabled(true);
                });
                // Clean up provider on connection failure
                if (provider != null) {
                    try {
                        provider.shutdown();
                    } catch (Exception e) {
                        // Ignore shutdown errors
                    }
                    provider = null;
                }
            }
        }, "jtapi-worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private void stopListener() {
        userField.setEnabled(true);
        passField.setEnabled(true);
        phoneField.setEnabled(true);
        cucmHostField.setEnabled(true);
        urlField.setEnabled(true);
        triggerCombo.setEnabled(true);
        rememberMeCheck.setEnabled(true);
        startBtn.setEnabled(true);
        stopBtn.setEnabled(false);
        if (provider != null) {
            try {
                provider.shutdown();
                updateStatus("Disconnected: Service stopped manually", new Color(220, 53, 69));
            } catch (Exception e) {
                updateStatus("Disconnected: Failed to stop provider - " + e.getMessage(), new Color(220, 53, 69));
            }
            provider = null;
        }
        listener = null;
    }

    // Remove existing CiscoJtapi*.log files and watch for new ones to delete immediately.
    private void startLogCleaner() {
        Thread t = new Thread(() -> {
            Path dir = Paths.get(System.getProperty("user.dir"));
            try {
                // Delete any existing matching files
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "CiscoJtapi*.log")) {
                    for (Path p : ds) {
                        try { Files.deleteIfExists(p); } catch (Exception ignore) {}
                    }
                } catch (Exception ignore) {}

                WatchService ws = dir.getFileSystem().newWatchService();
                dir.register(ws, StandardWatchEventKinds.ENTRY_CREATE);
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = ws.take();
                    for (WatchEvent<?> ev : key.pollEvents()) {
                        try {
                            WatchEvent.Kind<?> kind = ev.kind();
                            if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                                Path created = dir.resolve((Path) ev.context());
                                String fname = created.getFileName().toString();
                                if (fname.startsWith("CiscoJtapi") && fname.endsWith(".log")) {
                                    try { Files.deleteIfExists(created); } catch (Exception ignore) {}
                                }
                            }
                        } catch (Exception ignore) {}
                    }
                    key.reset();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // If WatchService not supported or fails, attempt periodic cleanup as fallback
                try {
                    while (true) {
                        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "CiscoJtapi*.log")) {
                            for (Path p : ds) try { Files.deleteIfExists(p); } catch (Exception ignore) {}
                        } catch (Exception ignore) {}
                        Thread.sleep(5000);
                    }
                } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }, "log-cleaner");
        t.setDaemon(true);
        t.start();
    }

    // Start a small server socket to allow new launches to tell this instance to show the window
    private static void startSingleInstanceServer(ServerSocket ss) {
        Thread t = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    try (Socket s = ss.accept()) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                        String line = r.readLine();
                        if ("SHOW".equalsIgnoreCase(line != null ? line.trim() : "")) {
                            if (INSTANCE != null) INSTANCE.bringToFront();
                        }
                    } catch (IOException ioe) {
                        // ignore per-connection errors
                    }
                }
            } catch (Exception e) {
                // server terminating
            } finally {
                try { ss.close(); } catch (Exception ignore) {}
            }
        }, "single-instance-server");
        t.setDaemon(true);
        t.start();
    }

    private void bringToFront() {
        if (frame == null) return;
        try {
            SwingUtilities.invokeLater(() -> {
                if (frame.getState() == Frame.ICONIFIED) frame.setState(Frame.NORMAL);
                frame.toFront();
                frame.requestFocus();
                frame.repaint();
            });
        } catch (Exception ignored) {}
    }

    private void addFieldToPanel(JPanel panel, String labelText, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(10, 10, 10, 10);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = GridBagConstraints.RELATIVE;
        c.weightx = 0;
        if (!labelText.isEmpty()) {
            JLabel label = new JLabel(labelText);
            label.setFont(new Font("SansSerif", Font.PLAIN, 14));
            panel.add(label, c);
        }
        c.gridx = 1;
        c.weightx = 1;
        panel.add(field, c);
    }

    // Method to create an outbound call - used by CallListPanel for resuming invalid held calls
    public static boolean makeOutboundCall(String phoneNumber) {
        if (INSTANCE == null || INSTANCE.provider == null) {
            System.out.println("OUTBOUND: No active provider available");
            return false;
        }

        try {
            // Get the current address from the phone field
            String phone = INSTANCE.phoneField.getText().trim();
            if (phone.isEmpty()) {
                System.out.println("OUTBOUND: No phone number configured");
                return false;
            }

            Address address = INSTANCE.provider.getAddress(phone);
            if (address == null) {
                System.out.println("OUTBOUND: Could not get address for phone: " + phone);
                return false;
            }

            System.out.println("OUTBOUND: Creating call from " + address.getName() + " to " + phoneNumber);

            // Create the call using JTAPI - try different methods
            Call call = null;
            try {
                // Try Address.createCall(String)
                java.lang.reflect.Method createCallMethod = address.getClass().getMethod("createCall", String.class);
                call = (Call) createCallMethod.invoke(address, phoneNumber);
            } catch (Exception e) {
                System.out.println("OUTBOUND: Address.createCall failed, trying Provider.createCall");
                try {
                    // Try Provider.createCall() with different signatures
                    java.lang.reflect.Method[] methods = INSTANCE.provider.getClass().getMethods();
                    for (java.lang.reflect.Method m : methods) {
                        if (m.getName().equals("createCall")) {
                            if (m.getParameterCount() == 0) {
                                call = (Call) m.invoke(INSTANCE.provider);
                                break;
                            }
                        }
                    }
                } catch (Exception e2) {
                    System.out.println("OUTBOUND: Provider.createCall also failed: " + e2.getMessage());
                }
            }

            if (call != null) {
                System.out.println("OUTBOUND: Call created successfully: " + call);
                
                // Try to initiate the call
                try {
                    // Try to connect the call
                    java.lang.reflect.Method connectMethod = call.getClass().getMethod("connect", javax.telephony.Terminal.class, javax.telephony.Address.class, String.class);
                    connectMethod.invoke(call, address.getTerminals()[0], address, phoneNumber);
                    System.out.println("OUTBOUND: Call connected successfully");
                } catch (Exception e) {
                    System.out.println("OUTBOUND: Connect method failed, trying alternative approaches");
                    try {
                        // Try alternative connection methods
                        java.lang.reflect.Method[] methods = call.getClass().getMethods();
                        for (java.lang.reflect.Method m : methods) {
                            String name = m.getName().toLowerCase();
                            if (name.contains("connect") && m.getParameterCount() >= 2) {
                                try {
                                    if (m.getParameterCount() == 2) {
                                        m.invoke(call, address.getTerminals()[0], address);
                                    } else if (m.getParameterCount() == 3) {
                                        m.invoke(call, address.getTerminals()[0], address, phoneNumber);
                                    }
                                    System.out.println("OUTBOUND: Call initiated with " + m.getName());
                                    break;
                                } catch (Exception e2) {
                                    // Try next method
                                }
                            }
                        }
                    } catch (Exception e2) {
                        System.out.println("OUTBOUND: All connection attempts failed: " + e2.getMessage());
                    }
                }
                
                // Add to registry so it appears in the UI
                CallRegistry.getInstance().addOrUpdate(call, phoneNumber, "CREATED", address.getName());
                return true;
            } else {
                System.out.println("OUTBOUND: Failed to create call");
                return false;
            }
        } catch (Exception e) {
            System.out.println("OUTBOUND: Exception creating call: " + e.getMessage());
            return false;
        }
    }
}