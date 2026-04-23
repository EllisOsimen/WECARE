package uk.ac.ed.inf.wecare.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import uk.ac.ed.inf.wecare.model.PatientStatusView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PatientStatusService {

    private static final String STATUS_KEY_PATTERN = "patient_*_status";
    private static final String PINNED_SET_KEY = "pinned_patients";
    private static final Pattern PATIENT_KEY_REGEX = Pattern.compile("^patient_(\\d+)_status$");
        private static final Map<Integer, PatientProfile> PATIENT_REGISTRY = Map.of(
            1, new PatientProfile("Alice Brown", "Room 12", false, 72, 97),
            2, new PatientProfile("Robert Smith", "Room 14", true, 68, 96),
            3, new PatientProfile("Fatima Khan", "Room 19", false, 75, 98),
            4, new PatientProfile("George Wilson", "Room 12", true, 70, 95),
            5, new PatientProfile("Mary Johnson", "Room 14", false, 66, 97),
            6, new PatientProfile("Daniel Lee", "Room 19", false, 74, 96),
            7, new PatientProfile("Nora Adams", "Room 12", true, 69, 95),
            8, new PatientProfile("Samuel Clarke", "Room 14", false, 71, 97)
        );

    private final RedisTemplate<String, String> redisTemplate;

    public PatientStatusService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public List<PatientStatusView> listPatientStatuses() {
        Set<Integer> pinnedPatients = getPinnedPatients();
        Map<Integer, PatientStatusView> byPatientId = new HashMap<>();

        for (Map.Entry<Integer, PatientProfile> entry : PATIENT_REGISTRY.entrySet()) {
            int patientId = entry.getKey();
            PatientProfile profile = entry.getValue();

            byPatientId.put(patientId, buildMonitoringView(
                    patientId,
                    profile,
                    pinnedPatients.contains(patientId)
            ));
        }

        Set<String> keys = redisTemplate.keys(STATUS_KEY_PATTERN);
        if (keys != null) {
            for (String key : keys) {
                Integer patientId = extractPatientIdFromKey(key);
                if (patientId == null) {
                    continue;
                }

                Map<Object, Object> statusMap = redisTemplate.opsForHash().entries(key);
                PatientProfile profile = profileForPatient(patientId);

                byPatientId.put(patientId, new PatientStatusView(
                        patientId,
                        profile.name(),
                        valueOrDefault(statusMap.get("status"), "UNKNOWN"),
                        valueOrDefault(statusMap.get("last_rule"), "UNKNOWN_RULE"),
                        valueOrDefault(statusMap.get("last_message"), "No message"),
                        valueOrDefault(statusMap.get("location_zone"), "Unknown"),
                        profile.homeZone(),
                        profile.dementiaFlag(),
                        profile.baselineHeartRate(),
                        profile.baselineOxygenLevel(),
                        valueOrDefault(statusMap.get("updated_at"), "Unknown"),
                        pinnedPatients.contains(patientId),
                        true
                ));
            }
        }

        for (Integer pinnedPatientId : pinnedPatients) {
            if (byPatientId.containsKey(pinnedPatientId)) {
                continue;
            }

            PatientProfile profile = profileForPatient(pinnedPatientId);
            byPatientId.put(pinnedPatientId, buildMonitoringView(pinnedPatientId, profile, true));
        }

        List<PatientStatusView> result = new ArrayList<>(byPatientId.values());
        result.sort(Comparator
                .comparing((PatientStatusView status) -> status.pinned() ? 0 : 1)
                .thenComparing((PatientStatusView status) -> isCriticalStatus(status.status()) ? 0 : 1)
                .thenComparing(PatientStatusView::patientId));

        return result;
    }

    public void pinPatient(int patientId) {
        redisTemplate.opsForSet().add(PINNED_SET_KEY, String.valueOf(patientId));
    }

    public void unpinPatient(int patientId) {
        redisTemplate.opsForSet().remove(PINNED_SET_KEY, String.valueOf(patientId));
    }

    private Set<Integer> getPinnedPatients() {
        Set<String> members = redisTemplate.opsForSet().members(PINNED_SET_KEY);
        if (members == null || members.isEmpty()) {
            return new HashSet<>();
        }

        Set<Integer> pinned = new HashSet<>();
        for (String member : members) {
            try {
                pinned.add(Integer.parseInt(member));
            } catch (NumberFormatException ignored) {
                // Ignore malformed pinned entries.
            }
        }
        return pinned;
    }

    private Integer extractPatientIdFromKey(String key) {
        Matcher matcher = PATIENT_KEY_REGEX.matcher(key);
        if (!matcher.matches()) {
            return null;
        }

        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String valueOrDefault(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private boolean isCriticalStatus(String status) {
        if (status == null) {
            return false;
        }

        String normalized = status.toUpperCase();
        return normalized.contains("CRITICAL") || normalized.contains("URGENT");
    }

    private PatientStatusView buildMonitoringView(int patientId, PatientProfile profile, boolean pinned) {
        return new PatientStatusView(
                patientId,
                profile.name(),
                "NORMAL",
                "NO_ACTIVE_ALERT",
                "Monitored patient, no active alerts",
                profile.homeZone(),
                profile.homeZone(),
                profile.dementiaFlag(),
                profile.baselineHeartRate(),
                profile.baselineOxygenLevel(),
                "N/A",
                pinned,
                false
        );
    }

    private PatientProfile profileForPatient(int patientId) {
        return PATIENT_REGISTRY.getOrDefault(
                patientId,
                new PatientProfile("Patient " + patientId, "Unknown", false, 70, 96)
        );
    }

    private record PatientProfile(
            String name,
            String homeZone,
            boolean dementiaFlag,
            int baselineHeartRate,
            int baselineOxygenLevel
    ) {
    }
}
