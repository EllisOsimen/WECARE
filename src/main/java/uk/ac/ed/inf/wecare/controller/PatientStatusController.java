package uk.ac.ed.inf.wecare.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.ac.ed.inf.wecare.model.PatientStatusView;
import uk.ac.ed.inf.wecare.service.PatientStatusService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/patients")
public class PatientStatusController {

    private final PatientStatusService patientStatusService;

    public PatientStatusController(PatientStatusService patientStatusService) {
        this.patientStatusService = patientStatusService;
    }

    @GetMapping("/status")
    public List<PatientStatusView> getStatuses() {
        return patientStatusService.listPatientStatuses();
    }

    @PostMapping("/{patientId}/pin")
    public ResponseEntity<Map<String, String>> pinPatient(@PathVariable int patientId) {
        patientStatusService.pinPatient(patientId);
        return ResponseEntity.ok(Map.of(
                "message", "Patient pinned",
                "patient_id", String.valueOf(patientId)
        ));
    }

    @DeleteMapping("/{patientId}/pin")
    public ResponseEntity<Map<String, String>> unpinPatient(@PathVariable int patientId) {
        patientStatusService.unpinPatient(patientId);
        return ResponseEntity.ok(Map.of(
                "message", "Patient unpinned",
                "patient_id", String.valueOf(patientId)
        ));
    }
}
