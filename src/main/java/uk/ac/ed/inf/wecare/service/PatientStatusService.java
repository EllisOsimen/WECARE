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

    private final RedisTemplate<String, String> redisTemplate;

    public PatientStatusService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public List<PatientStatusView> listPatientStatuses() {
        Set<Integer> pinnedPatients = getPinnedPatients();
        Map<Integer, PatientStatusView> byPatientId = new HashMap<>();

        Set<String> keys = redisTemplate.keys(STATUS_KEY_PATTERN);
        if (keys != null) {
            for (String key : keys) {
                Integer patientId = extractPatientIdFromKey(key);
                if (patientId == null) {
                    continue;
                }

                Map<Object, Object> statusMap = redisTemplate.opsForHash().entries(key);
                byPatientId.put(patientId, new PatientStatusView(
                        patientId,
                        valueOrDefault(statusMap.get("status"), "UNKNOWN"),
                        valueOrDefault(statusMap.get("last_rule"), "UNKNOWN_RULE"),
                        valueOrDefault(statusMap.get("last_message"), "No message"),
                        valueOrDefault(statusMap.get("location_zone"), "Unknown"),
                        valueOrDefault(statusMap.get("updated_at"), "Unknown"),
                        pinnedPatients.contains(patientId)
                ));
            }
        }

        for (Integer pinnedPatientId : pinnedPatients) {
            if (byPatientId.containsKey(pinnedPatientId)) {
                continue;
            }

            byPatientId.put(pinnedPatientId, new PatientStatusView(
                    pinnedPatientId,
                    "PINNED_NO_ALERT_YET",
                    "NONE",
                    "No alerts received yet",
                    "Unknown",
                    "Unknown",
                    true
            ));
        }

        List<PatientStatusView> result = new ArrayList<>(byPatientId.values());
        result.sort(Comparator
                .comparing(PatientStatusView::pinned)
                .reversed()
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
}
