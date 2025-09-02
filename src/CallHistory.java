import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Call History Manager for storing and retrieving call records
 */
public class CallHistory {
    private static CallHistory INSTANCE;
    private final List<CallRecord> history = new CopyOnWriteArrayList<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    // File for persistent storage
    private static final String HISTORY_FILE = getHistoryFilePath();

    private static String getHistoryFilePath() {
        String userHome = System.getProperty("user.home");
        if (userHome == null) {
            userHome = System.getenv("USERPROFILE"); // Windows
            if (userHome == null) {
                userHome = System.getenv("HOME"); // Unix/Linux
                if (userHome == null) {
                    userHome = "."; // Fallback to current directory
                }
            }
        }
        return userHome + "/.jtapi_config/call_history.txt";
    }

    public static CallHistory getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CallHistory();
        }
        return INSTANCE;
    }

    public static class CallRecord {
        public final String id;
        public final String number;
        public final String name;
        public final String direction; // "INBOUND", "OUTBOUND", "MISSED"
        public final long startTime;
        public final long endTime;
        public final long talkDuration; // in milliseconds
        public final String address;
        public final boolean answered;

        public CallRecord(String id, String number, String name, String direction,
                         long startTime, long endTime, long talkDuration, String address, boolean answered) {
            this.id = id;
            this.number = number;
            this.name = name != null ? name : "";
            this.direction = direction;
            this.startTime = startTime;
            this.endTime = endTime;
            this.talkDuration = talkDuration;
            this.address = address;
            this.answered = answered;
        }

        public String getFormattedStartTime() {
            return new SimpleDateFormat("HH:mm:ss").format(new Date(startTime));
        }

        public String getFormattedDate() {
            return new SimpleDateFormat("yyyy-MM-dd").format(new Date(startTime));
        }

        public String getFormattedDuration() {
            if (talkDuration <= 0) return "00:00";

            long seconds = talkDuration / 1000;
            long minutes = seconds / 60;
            seconds = seconds % 60;

            return String.format("%02d:%02d", minutes, seconds);
        }

        public boolean isToday() {
            Calendar today = Calendar.getInstance();
            Calendar callDate = Calendar.getInstance();
            callDate.setTimeInMillis(startTime);

            return today.get(Calendar.YEAR) == callDate.get(Calendar.YEAR) &&
                   today.get(Calendar.DAY_OF_YEAR) == callDate.get(Calendar.DAY_OF_YEAR);
        }

        @Override
        public String toString() {
            return String.format("%s|%s|%s|%s|%d|%d|%d|%s|%s",
                id, number, name, direction, startTime, endTime, talkDuration, address, answered);
        }

        public static CallRecord fromString(String line) {
            try {
                String[] parts = line.split("\\|");
                if (parts.length >= 9) {
                    return new CallRecord(
                        parts[0], // id
                        parts[1], // number
                        parts[2], // name
                        parts[3], // direction
                        Long.parseLong(parts[4]), // startTime
                        Long.parseLong(parts[5]), // endTime
                        Long.parseLong(parts[6]), // talkDuration
                        parts[7], // address
                        Boolean.parseBoolean(parts[8]) // answered
                    );
                }
            } catch (Exception e) {
                System.err.println("Failed to parse call record: " + line);
            }
            return null;
        }
    }

    private CallHistory() {
        loadHistory();
        // Clean up old records (keep only last 30 days)
        cleanupOldRecords();
    }

    public void addCall(String id, String number, String name, String direction,
                       long startTime, String address) {
        CallRecord record = new CallRecord(id, number, name, direction, startTime, 0, 0, address, false);
        history.add(0, record); // Add to beginning for most recent first
        saveHistory();
    }

    public void updateCall(String id, long endTime, long talkDuration, boolean answered) {
        for (CallRecord record : history) {
            if (record.id.equals(id)) {
                CallRecord updated = new CallRecord(
                    record.id, record.number, record.name, record.direction,
                    record.startTime, endTime, talkDuration, record.address, answered
                );
                history.set(history.indexOf(record), updated);
                saveHistory();
                break;
            }
        }
    }

    public List<CallRecord> getTodaysCalls() {
        List<CallRecord> todays = new ArrayList<>();
        for (CallRecord record : history) {
            if (record.isToday()) {
                todays.add(record);
            }
        }
        return todays;
    }

    public List<CallRecord> getAllCalls() {
        return new ArrayList<>(history);
    }

    public List<CallRecord> getMissedCalls() {
        List<CallRecord> missed = new ArrayList<>();
        for (CallRecord record : history) {
            if ("MISSED".equals(record.direction) || ("INBOUND".equals(record.direction) && !record.answered)) {
                missed.add(record);
            }
        }
        return missed;
    }

    public void clearHistory() {
        history.clear();
        saveHistory();
    }

    private void loadHistory() {
        File file = new File(HISTORY_FILE);
        if (!file.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                CallRecord record = CallRecord.fromString(line);
                if (record != null) {
                    history.add(record);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load call history: " + e.getMessage());
        }
    }

    private void saveHistory() {
        File file = new File(HISTORY_FILE);
        file.getParentFile().mkdirs();

        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            for (CallRecord record : history) {
                writer.println(record.toString());
            }
        } catch (IOException e) {
            System.err.println("Failed to save call history: " + e.getMessage());
        }
    }

    private void cleanupOldRecords() {
        long thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000);
        history.removeIf(record -> record.startTime < thirtyDaysAgo);
        if (history.size() > 1000) { // Keep maximum 1000 records
            history.subList(1000, history.size()).clear();
        }
        saveHistory();
    }
}
