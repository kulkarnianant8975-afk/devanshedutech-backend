package com.devanshedutech.crm;

import com.devanshedutech.dto.LeadDTOs.ActivityResponse;
import com.devanshedutech.dto.LeadDTOs.LadderStepResponse;
import com.devanshedutech.dto.LeadDTOs.LeadResponse;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.LeadActivity;
import com.devanshedutech.model.LadderStep;
import com.devanshedutech.repository.BatchRepository;
import com.devanshedutech.model.User;
import com.devanshedutech.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns lead entities into the shapes the pipeline screens need.
 *
 * <p>Two values are computed rather than stored, because storing them would mean keeping them
 * fresh: how many days overdue a touch is, and how long the first human reply took. The second
 * is the playbook's five-minute metric, and deriving it from timestamps means it can never
 * disagree with the timeline.</p>
 */
@Component
public class LeadMapper {

    private final UserRepository userRepository;
    private final BatchRepository batchRepository;

    public LeadMapper(UserRepository userRepository, BatchRepository batchRepository) {
        this.userRepository = userRepository;
        this.batchRepository = batchRepository;
    }

    /**
     * Owner names for a whole page of leads, in one query.
     *
     * <p>Resolving the name inside {@link #toResponse} looked harmless but issued a user lookup
     * per row, so a twenty-five row page cost twenty-five extra queries. The staff list is
     * small, so fetching it once and looking up in memory is both simpler and faster.</p>
     */
    public Function<String, String> ownerNames() {
        Map<String, String> byId = userRepository.findAll().stream()
                .collect(Collectors.toMap(User::getId, User::displayNameOrEmail, (a, b) -> a));
        return id -> id == null ? null : byId.get(id);
    }

    /** Convenience for single-lead responses, where one extra lookup costs nothing. */
    public LeadResponse toResponse(Lead l) {
        return toResponse(l, id -> id == null ? null
                : userRepository.findById(id).map(User::displayNameOrEmail).orElse(null));
    }

    public LeadResponse toResponse(Lead l, Function<String, String> ownerName) {
        LocalDate today = LocalDate.now();

        Integer overdue = null;
        if (l.getNextTouchOn() != null && l.getNextTouchOn().isBefore(today) && l.isActive()) {
            overdue = (int) ChronoUnit.DAYS.between(l.getNextTouchOn(), today);
        }

        Long firstResponse = null;
        if (l.getFirstRespondedAt() != null && l.getCreatedAt() != null) {
            firstResponse = Duration.between(l.getCreatedAt(), l.getFirstRespondedAt()).toMinutes();
        }

        return LeadResponse.builder()
                .id(l.getId())
                .fullName(l.getFullName())
                .email(l.getEmail())
                .mobileNumber(l.getMobileNumber())
                .education(l.getEducation())
                .cityName(l.getCityName())
                .courseInterested(l.getCourseInterested())
                .status(l.getStage() == null ? l.getStatus() : l.getStage().getLabel())
                .createdAt(l.getCreatedAt())
                .stage(l.getStage())
                .stageLabel(l.getStage() == null ? null : l.getStage().getLabel())
                .grade(l.getGrade())
                .gradeLabel(l.getGrade() == null ? null : l.getGrade().getLabel())
                .source(l.getSource())
                .sourceLabel(l.getSource() == null ? null : l.getSource().getLabel())
                .sourceDetail(l.getSourceDetail())
                .background(l.getBackground())
                .backgroundLabel(l.getBackground() == null ? null : l.getBackground().getLabel())
                .assignedToId(l.getAssignedToId())
                .assignedToName(ownerName.apply(l.getAssignedToId()))
                .nextTouchOn(l.getNextTouchOn())
                .nextTouchNote(l.getNextTouchNote())
                .daysOverdue(overdue)
                .blankNextTouch(l.hasBlankNextTouch())
                .lastTouchAt(l.getLastTouchAt())
                .lastTouchNote(l.getLastTouchNote())
                .firstRespondedAt(l.getFirstRespondedAt())
                .firstResponseMinutes(firstResponse)
                .lastInboundAt(l.getLastInboundAt())
                .callAttempts(l.getCallAttempts())
                .ladderStep(l.getLadderStep())
                .ladderPausedUntil(l.getLadderPausedUntil())
                .ladderPauseReason(l.getLadderPauseReason())
                .lostUnworked(l.getLostUnworked())
                .batchId(l.getBatchId())
                .batchName(l.getBatchId() == null ? null
                        : batchRepository.findById(l.getBatchId()).map(b -> b.describe()).orElse(null))
                .feePlan(l.getFeePlan())
                .paymentStatus(l.getPaymentStatus())
                .lostReason(l.getLostReason())
                .lostNote(l.getLostNote())
                .updatesOnly(l.getUpdatesOnly())
                .optedOut(l.getOptedOut())
                .notes(l.getNotes())
                .build();
    }

    public LadderStepResponse toResponse(LadderStep s, Integer currentStep) {
        return LadderStepResponse.builder()
                .id(s.getId())
                .grade(s.getGrade())
                .stepNo(s.getStepNo())
                .dayOffset(s.getDayOffset())
                .title(s.getTitle())
                .action(s.getAction())
                .autoSend(s.getAutoSend())
                .active(s.getActive())
                .reached(currentStep == null ? null : s.getStepNo() <= currentStep)
                .build();
    }

    public ActivityResponse toResponse(LeadActivity a) {
        return ActivityResponse.builder()
                .id(a.getId())
                .type(a.getType())
                .typeLabel(a.getType() == null ? null : a.getType().getLabel())
                .outcome(a.getOutcome())
                .outcomeLabel(a.getOutcome() == null ? null : a.getOutcome().getLabel())
                .direction(a.getDirection())
                .summary(a.getSummary())
                .detail(a.getDetail())
                .createdByName(a.getCreatedByName())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
