package quest.server.chat;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import quest.api.dto.ChatMessage;
import quest.api.dto.ChatReadReceipt;
import quest.api.dto.Complaint;
import quest.api.dto.ComplaintArea;
import quest.api.dto.ComplaintDetail;
import quest.api.dto.ComplaintList;
import quest.api.dto.ComplaintRecipient;
import quest.api.dto.ComplaintStatusRequest;
import quest.api.dto.CreateComplaintRequest;
import quest.api.dto.SendChatMessageRequest;
import quest.server.auth.Principals;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;

/**
 * B6: the Complaints routes of the parent (`/children/{id}/complaints/**`), the teacher (`/teacher/complaints/**`) and
 * the Admin's read-only support (`/admin/complaints/**`, `X-School-Id`). The coordinator's and the manager's live
 * beside the rest of their areas ({@code CoordinatorChatController}, {@code ManagementChatController}) and land on the
 * same {@link ComplaintService}, which is where every rule is. Behind `chat`, the flag complaints have always had: a
 * complaint is a conversation, stored and delivered as one, and a school that turned `chat` on in R4 already has
 * complaints its staff answer. The shared codec encodes the shared-api DTOs (`quest.api.dto.Complaints.kt`).
 */
@RestController
@FeatureFlag(FlagKeys.CHAT)
@Tag(name = "Complaints", description = "Complaints as their own conversations: the parent's, the teacher's and support's")
public class ComplaintController {
    private final ComplaintService complaints; private final ComplaintCodec json;
    public ComplaintController(ComplaintService complaints, ComplaintCodec json) { this.complaints = complaints; this.json = json; }

    // ---------------------------------------------------------------- the parent (app)

    @GetMapping(value = "/children/{id}/complaints", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.complaints')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ComplaintList.class)))
    public String parentComplaints(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id, @RequestParam(required = false) String status) {
        return json.list(complaints.parentList(parent, id, status));
    }

    @GetMapping(value = "/children/{id}/complaints/recipients", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.complaints')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ComplaintRecipient.class))))
    public String parentComplaintRecipients(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id) {
        return json.recipients(complaints.recipients(parent, id));
    }

    @PostMapping(value = "/children/{id}/complaints", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.complaints')")
    @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CreateComplaintRequest.class)))
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ComplaintDetail.class)))
    public String parentCreateComplaint(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id, @RequestBody String body) {
        return json.detail(complaints.create(parent, id, json.create(body)));
    }

    @GetMapping(value = "/children/{id}/complaints/{complaintId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.complaints')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ComplaintDetail.class)))
    public String parentComplaint(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id, @PathVariable String complaintId,
                                  @RequestParam(required = false) String before, @RequestParam(required = false) String since,
                                  @RequestParam(required = false) Integer limit) {
        return json.detail(complaints.parentDetail(parent, id, complaintId, before, since, limit));
    }

    @PostMapping(value = "/children/{id}/complaints/{complaintId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.complaints')")
    @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = SendChatMessageRequest.class)))
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatMessage.class)))
    public String parentSendComplaintMessage(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id, @PathVariable String complaintId,
                                             @RequestBody String body) {
        var req = json.message(body);
        return json.message(complaints.parentSend(parent, id, complaintId, req.getBody(), req.getAttachmentIds(), req.getClientId()));
    }

    @PostMapping(value = "/children/{id}/complaints/{complaintId}/read", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.complaints')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatReadReceipt.class)))
    public String parentMarkComplaintRead(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id, @PathVariable String complaintId) {
        return json.receipt(complaints.parentRead(parent, id, complaintId));
    }

    /** She reopens it: `{"status":"open"}`. `resolved` is 403 — resolving is the school's word. */
    @PatchMapping(value = "/children/{id}/complaints/{complaintId}/status", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.complaints')")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ComplaintStatusRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = Complaint.class)))
    public String parentComplaintStatus(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id, @PathVariable String complaintId,
                                        @RequestBody String body) {
        return json.row(complaints.parentStatus(parent, id, complaintId, json.status(body)));
    }

    // ---------------------------------------------------------------- the teacher (dashboard)

    @GetMapping(value = "/teacher/complaints", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.complaints')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ComplaintList.class)))
    public String teacherComplaints(@AuthenticationPrincipal Principals.User caller, @RequestParam(required = false) String status) {
        return json.list(complaints.staffList(ComplaintArea.TEACHER, caller, status));
    }

    @GetMapping(value = "/teacher/complaints/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.complaints')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ComplaintDetail.class)))
    public String teacherComplaint(@AuthenticationPrincipal Principals.User caller, @PathVariable String id, @RequestParam(required = false) String before,
                                   @RequestParam(required = false) String since, @RequestParam(required = false) Integer limit) {
        return json.detail(complaints.staffDetail(ComplaintArea.TEACHER, caller, id, before, since, limit));
    }

    @PostMapping(value = "/teacher/complaints/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.complaints')")
    @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = SendChatMessageRequest.class)))
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatMessage.class)))
    public String teacherSendComplaintMessage(@AuthenticationPrincipal Principals.User caller, @PathVariable String id, @RequestBody String body) {
        var req = json.message(body);
        return json.message(complaints.staffSend(ComplaintArea.TEACHER, caller, id, req.getBody(), req.getAttachmentIds(), req.getClientId()));
    }

    @PostMapping(value = "/teacher/complaints/{id}/read", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.complaints')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatReadReceipt.class)))
    public String teacherMarkComplaintRead(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return json.receipt(complaints.staffRead(ComplaintArea.TEACHER, caller, id));
    }

    @PatchMapping(value = "/teacher/complaints/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.complaints')")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ComplaintStatusRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = Complaint.class)))
    public String teacherComplaintStatus(@AuthenticationPrincipal Principals.User caller, @PathVariable String id, @RequestBody String body) {
        return json.row(complaints.staffStatus(ComplaintArea.TEACHER, caller, id, json.status(body)));
    }

    // ---------------------------------------------------------------- support (Admin, read-only)

    /** Every complaint of the school named by `X-School-Id`; she reads them and answers none. */
    @GetMapping(value = "/admin/complaints", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('admin.complaints')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ComplaintList.class)))
    public String supportComplaints(@AuthenticationPrincipal Principals.User caller, @RequestParam(required = false) String status) {
        return json.list(complaints.staffList(ComplaintArea.ADMIN, caller, status));
    }

    @GetMapping(value = "/admin/complaints/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('admin.complaints')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ComplaintDetail.class)))
    public String supportComplaint(@AuthenticationPrincipal Principals.User caller, @PathVariable String id, @RequestParam(required = false) String before,
                                   @RequestParam(required = false) String since, @RequestParam(required = false) Integer limit) {
        return json.detail(complaints.staffDetail(ComplaintArea.ADMIN, caller, id, before, since, limit));
    }
}
