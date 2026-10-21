package quest.server.evaluations;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;

/**
 * Feature 33: the Admin's "Evaluation rounds" page — create a round, edit it until it is released, and release it to
 * the parents once, with a confirmation on her side.
 *
 * <p>`evaluations.rounds` is ADMIN alone: a round decides what every teacher of the school is asked for and when the
 * parents see it. Behind `childEvaluations`.
 */
@RestController
@FeatureFlag(FlagKeys.CHILD_EVALUATIONS)
@Tag(name = "Evaluation rounds", description = "The Admin's evaluation rounds, and the one release to the parents")
public class EvaluationRoundController {
    private final EvaluationRounds rounds;

    public EvaluationRoundController(EvaluationRounds rounds) { this.rounds = rounds; }

    @GetMapping(value = "/admin/evaluation-rounds", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('evaluations.rounds')")
    public List<EvaluationDto.EvaluationRound> evaluationRounds() { return rounds.list(); }

    @PostMapping(value = "/admin/evaluation-rounds", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('evaluations.rounds')")
    public EvaluationDto.EvaluationRound createEvaluationRound(@AuthenticationPrincipal Principals.User caller,
                                                               @RequestBody EvaluationDto.CreateEvaluationRoundRequest body) {
        return rounds.create(caller, body);
    }

    @PutMapping(value = "/admin/evaluation-rounds/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('evaluations.rounds')")
    public EvaluationDto.EvaluationRound updateEvaluationRound(@AuthenticationPrincipal Principals.User caller,
                                                               @PathVariable String id,
                                                               @RequestBody EvaluationDto.UpdateEvaluationRoundRequest body) {
        return rounds.update(caller, id, body);
    }

    @PostMapping(value = "/admin/evaluation-rounds/{id}/release", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('evaluations.rounds')")
    public EvaluationDto.EvaluationRound releaseEvaluationRound(@AuthenticationPrincipal Principals.User caller,
                                                                @PathVariable String id) {
        return rounds.release(caller, id);
    }
}
