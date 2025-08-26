import javax.swing.*;
import java.awt.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.event.*;
import javax.telephony.*;
import java.io.*;
import java.nio.file.*;
import java.util.regex.Pattern;
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
    private JButton startBtn;
    private JButton stopBtn;
    private JLabel statusLabel;
    private JTabbedPane tabbedPane;

    private Provider provider;
    private Thread workerThread;
    private JTAPICallerInfo listener;

    private static final String CONFIG_DIR = System.getProperty("user.home") + "/.jtapi_config";
    private static final String CONFIG_FILE = CONFIG_DIR + "/config.properties";

    public static void main(String[] args) {
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new JTAPIGui().buildAndShow());
    }

    private void buildAndShow() {
        frame = new JFrame("LKQ CTI Popup");
        // Load application icons (look for resources or working-dir files)
        java.util.List<Image> icons = loadAppIcons();
        if (icons != null && !icons.isEmpty()) {
            frame.setIconImages(icons);
            try {
                // Taskbar API (Java 9+) improves taskbar icon on Windows
                java.awt.Taskbar tb = java.awt.Taskbar.getTaskbar();
                tb.setIconImage(icons.get(0));
            } catch (Throwable ignored) {}
        }
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.getContentPane().setBackground(new Color(34, 34, 34)); // Dark gray background

        // Initialize all fields first
        userField = createTextField("Enter Username", "");
        passField = new JPasswordField();
        passField.setBackground(Color.WHITE);
        passField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(150, 150, 150), 1, true),
            BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));
        phoneField = createTextField("Enter Phone#", "");
        cucmHostField = createTextField("Enter CUCM Host", "ccmaur1vpub.lkqvoice.com");
        urlField = createTextField("Enter URL Template (use {number})", "https://salesassistant.lkqcorp.com/customers/{number}");
        triggerCombo = new JComboBox<>(new String[]{"RINGING", "CONNECTED"});
        triggerCombo.setSelectedItem("CONNECTED");
        triggerCombo.setBackground(Color.WHITE);
        triggerCombo.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(150, 150, 150), 1, true),
            BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));
        rememberMeCheck = new JCheckBox("Remember Me");
        rememberMeCheck.setBackground(Color.WHITE);
        rememberMeCheck.setFont(new Font("SansSerif", Font.PLAIN, 14));

        // Load saved settings after initializing fields
        loadSavedSettings();

    // left sidebar removed per user request

        // Tabbed pane for content
        tabbedPane = new JTabbedPane();
        tabbedPane.setBackground(new Color(232, 236, 239)); // Soft light gray
        tabbedPane.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        // Connection tab
        JPanel connPanel = createContentPanel();
        JPanel connSection = createSectionPanel("Connection Settings");
        addFieldToPanel(connSection, "Username", userField);
        addFieldToPanel(connSection, "Password", passField);
        addFieldToPanel(connSection, "Phone#", phoneField);
        addFieldToPanel(connSection, "", rememberMeCheck); // Empty label for checkbox
        connPanel.add(connSection);
        tabbedPane.addTab("Connection", connPanel);

        // Call Settings tab
        JPanel callPanel = createContentPanel();
        addFieldToPanel(callPanel, "CUCM Host", cucmHostField);
        addFieldToPanel(callPanel, "URL Template", urlField);
        addFieldToPanel(callPanel, "Trigger Event", triggerCombo);
        tabbedPane.addTab("Call Settings", callPanel);

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

    // Load application icons from working directory or jar resources.
    private java.util.List<Image> loadAppIcons() {
        java.util.List<Image> list = new java.util.ArrayList<>();
        // Look in working directory for multi-size PNGs or an ICO
        String[] sizes = new String[] {"256", "48", "32", "16"};
        try {
            Path wd = Paths.get(System.getProperty("user.dir"));
            for (String s : sizes) {
                Path p = wd.resolve("icon_" + s + ".png");
                if (Files.exists(p)) {
                    try { list.add(ImageIO.read(p.toFile())); } catch (Exception ignore) {}
                }
            }
            // If user provided a single icon.png (e.g., src/icon.png), scale it into multiple sizes
            Path singleP = wd.resolve("icon.png");
            Path srcP = wd.resolve("src").resolve("icon.png");
            Path useSingle = null;
            if (Files.exists(singleP)) useSingle = singleP;
            else if (Files.exists(srcP)) useSingle = srcP;
            if (useSingle != null && list.isEmpty()) {
                try {
                    BufferedImage srcImg = ImageIO.read(useSingle.toFile());
                    if (srcImg != null) {
                        for (String s : sizes) {
                            int sz = Integer.parseInt(s);
                            list.add(scaleImage(srcImg, sz, sz));
                        }
                    }
                } catch (Exception ignore) {}
            }
            Path ico = wd.resolve("icon.ico");
            if (list.isEmpty() && Files.exists(ico)) {
                try { list.add(Toolkit.getDefaultToolkit().getImage(ico.toString())); } catch (Exception ignore) {}
            }
            // Classpath resources fallback
            if (list.isEmpty()) {
                // Also check for a single classpath resource '/icon.png'
                java.net.URL singleRes = getClass().getResource("/icon.png");
                if (singleRes != null) {
                    try {
                        BufferedImage srcImg = ImageIO.read(singleRes);
                        if (srcImg != null) {
                            for (String s : sizes) {
                                int sz = Integer.parseInt(s);
                                list.add(scaleImage(srcImg, sz, sz));
                            }
                        }
                    } catch (Exception ignore) {}
                }
                java.net.URL res = getClass().getResource("/icon_256.png");
                if (res != null) try { list.add(ImageIO.read(res)); } catch (Exception ignore) {}
                res = getClass().getResource("/icon_48.png");
                if (res != null) try { list.add(ImageIO.read(res)); } catch (Exception ignore) {}
                res = getClass().getResource("/icon_32.png");
                if (res != null) try { list.add(ImageIO.read(res)); } catch (Exception ignore) {}
                res = getClass().getResource("/icon_16.png");
                if (res != null) try { list.add(ImageIO.read(res)); } catch (Exception ignore) {}
                res = getClass().getResource("/icon.ico");
                if (res != null) try { list.add(Toolkit.getDefaultToolkit().getImage(res)); } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
        return list;
    }

    // Scale a BufferedImage to the requested width/height with high quality
    private BufferedImage scaleImage(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private JTextField createTextField(String placeholder, String defaultText) {
        JTextField field = new JTextField(defaultText);
        field.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(150, 150, 150), 1, true),
            BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));
        field.setBackground(Color.WHITE);
        field.setToolTipText(placeholder);
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (field.getText().trim().isEmpty() && (field == userField || field == cucmHostField)) {
                    field.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(220, 53, 69), 1, true),
                        BorderFactory.createEmptyBorder(5, 5, 5, 5)
                    ));
                } else {
                    field.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(150, 150, 150), 1, true),
                        BorderFactory.createEmptyBorder(5, 5, 5, 5)
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

    // Disable only the Start button to prevent double-click; keep inputs editable until subscription confirmed
    startBtn.setEnabled(false);
    stopBtn.setEnabled(false);

    updateStatus("Trying to Connect to " + host, new Color(255, 193, 7)); // Yellow

        String providerString = host + ";login=" + user + ";passwd=" + pass;

    final String providerStringFinal = providerString;
        final String phoneFinal = phone;
        final String urlTemplateFinal = urlTemplate;
        final String triggerFinal = (triggerCombo.getSelectedItem() != null ? triggerCombo.getSelectedItem().toString() : "CONNECTED");

        workerThread = new Thread(() -> {
            try {
                JtapiPeer peer = JtapiPeerFactory.getJtapiPeer(null);
                provider = peer.getProvider(providerStringFinal);
                boolean subscribed = false;
                if ("ALL".equalsIgnoreCase(phoneFinal)) {
                    JTAPICallerInfo allListener = new JTAPICallerInfo(urlTemplateFinal, triggerFinal, null);
                    Address[] all = provider.getAddresses();
                    for (Address a : (all != null ? all : new Address[0])) {
                        try {
                            a.addCallObserver(allListener);
                            subscribed = true;
                        } catch (Exception ex) {
                            updateStatus("Connected: Subscribe failed for some addresses - " + ex.getMessage(), new Color(40, 167, 69));
                        }
                    }
                    if (subscribed) updateStatus("Connected: Subscribed to ALL (" + (all != null ? all.length : 0) + " addresses)", new Color(40, 167, 69));
                    else updateStatus("Disconnected: Failed to subscribe to any addresses", new Color(220, 53, 69));
                } else {
                    try {
                        Address a = provider.getAddress(phoneFinal);
                        JTAPICallerInfo addrListener = new JTAPICallerInfo(urlTemplateFinal, triggerFinal, a.getName());
                        a.addCallObserver(addrListener);
                        subscribed = true;
                        updateStatus("Connected: Subscribed to " + a.getName(), new Color(40, 167, 69));
                    } catch (Exception ex) {
                        updateStatus("Disconnected: Failed to subscribe to '" + phoneFinal + "' - " + ex.getMessage(), new Color(220, 53, 69));
                        try {
                            Address[] available = provider.getAddresses();
                            for (Address av : (available != null ? available : new Address[0])) {
                                try {
                                    if (av.getName().contains(phoneFinal)) {
                                        JTAPICallerInfo fuzzyListener = new JTAPICallerInfo(urlTemplateFinal, triggerFinal, av.getName());
                                        av.addCallObserver(fuzzyListener);
                                        subscribed = true;
                                        updateStatus("Connected: Subscribed fuzzy to " + av.getName(), new Color(40, 167, 69));
                                        break;
                                    }
                                } catch (Exception e) {
                                    updateStatus("Connected: Fuzzy subscribe failed for " + av.getName() + " - " + e.getMessage(), new Color(40, 167, 69));
                                }
                            }
                        } catch (Exception e2) {
                            updateStatus("Disconnected: Failed to list addresses - " + e2.getMessage(), new Color(220, 53, 69));
                        }
                    }
                }

                // Apply UI state based on whether we actually subscribed
                if (subscribed) {
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
                    SwingUtilities.invokeLater(() -> {
                        // Reset UI so user can correct inputs; ensure Stop is disabled
                        userField.setEnabled(true);
                        passField.setEnabled(true);
                        phoneField.setEnabled(true);
                        cucmHostField.setEnabled(true);
                        urlField.setEnabled(true);
                        triggerCombo.setEnabled(true);
                        rememberMeCheck.setEnabled(true);
                        startBtn.setEnabled(true);
                        stopBtn.setEnabled(false);
                    });
                }
            } catch (Exception ex) {
                updateStatus("Disconnected: Failed to start listener - " + ex.getMessage(), new Color(220, 53, 69));
                SwingUtilities.invokeLater(() -> {
                    userField.setEnabled(true);
                    passField.setEnabled(true);
                    phoneField.setEnabled(true);
                    cucmHostField.setEnabled(true);
                    urlField.setEnabled(true);
                    triggerCombo.setEnabled(true);
                    rememberMeCheck.setEnabled(true);
                    startBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                });
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
}