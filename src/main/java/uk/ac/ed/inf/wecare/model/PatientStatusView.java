package uk.ac.ed.inf.wecare.model;

public record PatientStatusView(
        int patientId,
        String status,
        String lastRule,
        String lastMessage,
        String locationZone,
        String updatedAt,
        boolean pinned
) {
}
