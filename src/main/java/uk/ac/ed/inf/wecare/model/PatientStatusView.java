package uk.ac.ed.inf.wecare.model;

public record PatientStatusView(
        int patientId,
        String patientName,
        String status,
        String lastRule,
        String lastMessage,
        String locationZone,
        String homeZone,
        boolean dementiaFlag,
        int baselineHeartRate,
        int baselineOxygenLevel,
        String updatedAt,
        boolean pinned,
        boolean activeAlert
) {
}
