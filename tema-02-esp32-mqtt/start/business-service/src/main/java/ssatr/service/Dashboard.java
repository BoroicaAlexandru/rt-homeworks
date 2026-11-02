package ssatr.service;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class Dashboard {

    /** Starea unui device. */
    private static class DeviceState {
        Double lastTemperature = null;   // null pana la prima telemetrie
        long lastMessageMs = 0;          // momentul sosirii ultimului mesaj (ceasul serviciului)
        int alarmCount = 0;
    }

    // TreeMap: device-urile apar sortate dupa nume. Accesul e sincronizat.
    private final Map<String, DeviceState> devices = new TreeMap<>();

    private DeviceState state(String deviceId) {
        return devices.computeIfAbsent(deviceId, id -> new DeviceState());
    }

    public synchronized void onTelemetry(String deviceId, double temperature) {
        DeviceState s = state(deviceId);
        s.lastTemperature = temperature;
        s.lastMessageMs = System.currentTimeMillis();
    }

    public synchronized void onAlarm(String deviceId) {
        DeviceState s = state(deviceId);
        s.alarmCount++;
        s.lastMessageMs = System.currentTimeMillis();
    }

    public synchronized void onStatus(String deviceId) {
        state(deviceId).lastMessageMs = System.currentTimeMillis();
    }

    /** Afiseaza un tabel cu starea tuturor device-urilor. */
    public synchronized void print() {
        if (devices.isEmpty()) {
            System.out.println("(niciun device nu a trimis mesaje inca)");
            return;
        }
        long now = System.currentTimeMillis();
        System.out.printf("%-12s %-8s %-16s %s%n", "DEVICE", "TEMP", "ULTIMUL MESAJ", "ALARME");
        for (Map.Entry<String, DeviceState> e : devices.entrySet()) {
            DeviceState s = e.getValue();
            String temp = s.lastTemperature == null
                    ? "-" : String.format(Locale.US, "%.2f", s.lastTemperature);
            String age = "acum " + (now - s.lastMessageMs) / 1000 + " s";
            System.out.printf("%-12s %-8s %-16s %d%n", e.getKey(), temp, age, s.alarmCount);
        }
    }
}