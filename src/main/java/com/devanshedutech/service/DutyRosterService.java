package com.devanshedutech.service;

import com.devanshedutech.model.DutyShift;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.Permission;
import com.devanshedutech.model.Role;
import com.devanshedutech.model.User;
import com.devanshedutech.repository.DutyShiftRepository;
import com.devanshedutech.repository.UserRepository;
import com.devanshedutech.security.RolePermissions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Decides who owns an enquiry the moment it arrives.
 *
 * <p>New leads used to arrive belonging to nobody and sat in the list until a counsellor
 * happened to look, which is the most expensive thing that can happen to an enquiry — the
 * institute that replies first usually keeps the student. A roster gives every working hour an
 * owner, so there is always a specific person whose day the lead appears in.</p>
 */
@Slf4j
@Service
public class DutyRosterService {

    private final DutyShiftRepository shifts;
    private final UserRepository users;

    public DutyRosterService(DutyShiftRepository shifts, UserRepository users) {
        this.shifts = shifts;
        this.users = users;
    }

    /**
     * Who is on duty at this moment, if anyone.
     *
     * <p>A shift is only honoured if the person behind it can actually work leads today. Rosters
     * outlive staff: someone leaves, their account is deactivated, and their Tuesday shift keeps
     * silently swallowing every Tuesday enquiry into an inbox nobody opens. Checking the account
     * on each lookup means the worst case is an unassigned lead, which is visible, rather than
     * one assigned to a ghost, which is not.</p>
     */
    public Optional<String> onDutyAt(LocalDateTime at) {
        DayOfWeek day = at.getDayOfWeek();
        List<DutyShift> covering = shifts.findByDayOrderByStartsAtAsc(day).stream()
                .filter(s -> s.covers(at.toLocalTime()))
                .sorted(Comparator.comparing(DutyShift::getStartsAt))
                .toList();

        for (DutyShift shift : covering) {
            if (canWorkLeads(shift.getUserId())) return Optional.of(shift.getUserId());
            log.warn("Duty shift {} names user {}, who can no longer work leads. Skipping it.",
                    shift.getId(), shift.getUserId());
        }
        return Optional.empty();
    }

    /**
     * Gives an unowned lead to whoever is on duty. Leaves an already-owned lead alone — a
     * counsellor mid-conversation must not lose the student to a shift change.
     *
     * @return true if this call set an owner
     */
    public boolean assignIfUnowned(Lead lead, LocalDateTime at) {
        if (lead.getAssignedToId() != null) return false;
        Optional<String> owner = onDutyAt(at);
        owner.ifPresent(lead::setAssignedToId);
        return owner.isPresent();
    }

    private boolean canWorkLeads(String userId) {
        return users.findById(userId)
                .filter(u -> !Boolean.FALSE.equals(u.getActive()))
                .map(User::getRole)
                .map(Role::parse)
                .map(r -> RolePermissions.has(r, Permission.LEAD_VIEW_OWN))
                .orElse(false);
    }

    public List<DutyShift> roster() {
        return shifts.findAllByOrderByDayAscStartsAtAsc();
    }
}
