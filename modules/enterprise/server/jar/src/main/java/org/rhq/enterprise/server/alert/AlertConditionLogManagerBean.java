/*
 * RHQ Management Platform
 * Copyright (C) 2005-2008 Red Hat, Inc.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.rhq.enterprise.server.alert;

import java.util.List;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.NonUniqueResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.rhq.core.domain.alert.AlertCondition;
import org.rhq.core.domain.alert.AlertConditionLog;
import org.rhq.core.domain.alert.AlertDampening;
import org.rhq.core.domain.alert.AlertDampeningEvent;
import org.rhq.core.domain.alert.AlertDefinition;
import org.rhq.core.domain.alert.BooleanExpression;
import org.rhq.enterprise.server.RHQConstants;

/**
 * @author Joseph Marques
 */

@Stateless
public class AlertConditionLogManagerBean implements AlertConditionLogManagerLocal {
    private final Log log = LogFactory.getLog(AlertConditionLogManagerBean.class);

    @EJB
    private AlertConditionManagerLocal alertConditionManager;
    @EJB
    private AlertDampeningManagerLocal alertDampeningManager;

    @PersistenceContext(unitName = RHQConstants.PERSISTENCE_UNIT_NAME)
    private EntityManager entityManager;

    public AlertConditionLog getUnmatchedLogByAlertConditionId(int alertConditionId) {
        Query query = entityManager.createNamedQuery(AlertConditionLog.QUERY_FIND_UNMATCHED_LOG_BY_ALERT_CONDITION_ID);
        query.setParameter("alertConditionId", alertConditionId);

        return (AlertConditionLog) query.getSingleResult();
    }

    @SuppressWarnings("unchecked")
    public List<AlertConditionLog> getUnmatchedLogsByAlertDefinitionId(int alertDefinitionId) {
        Query unmatchedLogsQuery = entityManager
            .createNamedQuery(AlertConditionLog.QUERY_FIND_UNMATCHED_LOGS_BY_ALERT_DEFINITION_ID);
        unmatchedLogsQuery.setParameter("alertDefinitionId", alertDefinitionId);

        List<AlertConditionLog> unmatchedConditionLogs = unmatchedLogsQuery.getResultList();

        return unmatchedConditionLogs;
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void updateUnmatchedLogByAlertConditionId(int alertConditionId, long ctime, String value) {
        /*
         * this method is marked as REQUIRES_NEW because I want this log work to complete before I resume the
         * outer-scoping transaction, which will operate on the results.
         */
        try {
            AlertConditionLog alertConditionLog = this.getUnmatchedLogByAlertConditionId(alertConditionId);

            /*
             * No exceptions.
             *
             * This means that there was exactly one existing, unmatched, active alert condition.  This is another
             * positive event associated against the same alertCondition, so we're going to use its data to update the
             * "ctime" and "value" properties.
             */
            alertConditionLog.setCtime(ctime);
            alertConditionLog.setValue(value);

            entityManager.merge(alertConditionLog); // update values, for
        } catch (NoResultException nre) // this is the expected case 90% of the time
        {
            // lookup the condition entity
            AlertCondition condition = entityManager.find(AlertCondition.class, alertConditionId);

            // persist the log entry
            AlertConditionLog conditionLog = new AlertConditionLog(condition, ctime);
            conditionLog.setValue(value);

            log.debug("Inserting unmatched alert condition log: " + conditionLog);

            entityManager.persist(conditionLog);
            entityManager.flush();
        } catch (NonUniqueResultException nure) {
            // serious bug in the processing logic
            log.debug("Found multiple unmatched results for alertConditionId of " + alertConditionId
                + " while performing activation.  There should only be one.");
        }
    }

    public void removeUnmatchedLogByAlertConditionId(int alertConditionId) {
        try {
            AlertConditionLog alertConditionLog = this.getUnmatchedLogByAlertConditionId(alertConditionId);

            log.debug("Removing unmatched alert condition log: " + alertConditionLog);

            entityManager.remove(alertConditionLog);
        } catch (NoResultException nre) {
            /*
             * This is OK.
             *
             * At the time the cache fired it thought that there was an unmatched, active condition that included this
             * condition.  However, in the time between the sending of the JMS message to "now", the out-of-band process
             * came along and matched all necessary conditions and created an alert.  The act of creating an alert and
             * associating this alertConditionId with it means that it can no longer be removed by negative events
             */
        } catch (NonUniqueResultException nure) {
            // serious bug in the processing logic
            log.debug("Found multiple unmatched results for alertConditionId of " + alertConditionId
                + " while performing deactivation.  There should only be one.");
        }
    }

    @SuppressWarnings("unchecked")
    public void checkForCompletedAlertConditionSet(int alertConditionId) {
        AlertCondition alertCondition = alertConditionManager.getAlertConditionById(alertConditionId);
        AlertDefinition alertDefinition = alertCondition.getAlertDefinition();
        AlertDampening alertDampening = alertDefinition.getAlertDampening();

        // ok, so figure out whether all of the conditions have been met
        boolean conditionSetResult = evaluateConditionSet(alertDefinition);

        /*
         * The AlertDampeningEvents keep a running log of when all conditions have become true, as well as when they
         * become untrue (if they were most recently known to be true)
         */
        AlertDampeningEvent latestEvent = alertDampeningManager.getLatestEventByAlertDefinitionId(alertDefinition
            .getId());
        AlertDampeningEvent.Type type = getNextEventType(latestEvent, conditionSetResult);
        log.debug("Latest event was " + latestEvent + ", " + "next AlertDampeningEvent.Type is " + type);

        /*
         * Finally, operate on the new type event
         */
        if (type != AlertDampeningEvent.Type.UNCHANGED) {
            /*
             * But only if it represents a type of event we need to act on
             */
            AlertDampeningEvent alertDampeningEvent = new AlertDampeningEvent(alertDefinition, type);
            entityManager.persist(alertDampeningEvent);
            
            log.debug("Need to process AlertDampeningEvent.Type of " + type + " " + "for AlertDefinition[ id="
                + alertDefinition.getId() + " ]");

            alertDampeningManager.processEventType(alertDefinition.getId(), type);
        }
    }

    private AlertDampeningEvent.Type getNextEventType(AlertDampeningEvent lastEvent, boolean conditionSetResult) {
        /*
         * We always want to fire in the positive case.  This will give us the ability to compute both time-span and
         * count dampening categories using the same data set.
         */
        if (conditionSetResult) {
            /*
             * If lastEvent was null, we have no events for this AlertDefinition yet, and if we're in the negative state
             * that we've been in the positive state once before; In both cases, we're moving to the positive state.
             */
            if ((lastEvent == null) || (lastEvent.getEventType() == AlertDampeningEvent.Type.NEGATIVE)) {
                return AlertDampeningEvent.Type.POSITIVE;
            }

            /*
             * However, we want to add a POSITIVE_AGAIN event type to the history if we know the last event was already
             * either POSITIVE or POSITIVE_AGAIN.
             */
            else if ((lastEvent.getEventType() == AlertDampeningEvent.Type.POSITIVE)
                || (lastEvent.getEventType() == AlertDampeningEvent.Type.POSITIVE_AGAIN)) {
                return AlertDampeningEvent.Type.POSITIVE_AGAIN;
            }

            /*
             * for new functionality, make sure the callers recognize that this method needs to be expanded to support
             * the new AlertDampeningEvent.Type
             */
            else {
                throw new RuntimeException("Threshold reached, but AlertDampenintEvent.Type '"
                    + lastEvent.getEventType() + " not supported.");
            }
        }

        /*
         * Our unmatched logs don't match the expected count, thus our condition set isn't true as a whole due to the
         * most recent event; thus, we must check to see whether we need to terminate an open-ended interval or not.
         */
        else {
            /*
             * special handling to represent that we're already in the negative state, and to suppress sending an
             * AlertDampeningEventMessage to the alertNotificationProducer altogether
             */
            if ((lastEvent == null) || (lastEvent.getEventType() == AlertDampeningEvent.Type.NEGATIVE)) {
                return AlertDampeningEvent.Type.UNCHANGED;
            }

            /*
             * here, we were currently in one of the two positive states, so go to the negative state
             */
            else if ((lastEvent.getEventType() == AlertDampeningEvent.Type.POSITIVE)
                || (lastEvent.getEventType() == AlertDampeningEvent.Type.POSITIVE_AGAIN)) {
                return AlertDampeningEvent.Type.NEGATIVE;
            }

            /*
             * for new functionality, make sure the callers recognize that this method needs to be expanded to support
             * the new AlertDampeningEvent.Type
             */
            else {
                throw new RuntimeException("Threshold missed, but AlertDampenintEvent.Type '"
                    + lastEvent.getEventType() + " not supported.");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private boolean evaluateConditionSet(AlertDefinition alertDefinition) {
        List<AlertConditionLog> unmatchedLogs = this.getUnmatchedLogsByAlertDefinitionId(alertDefinition.getId());

        BooleanExpression expression = alertDefinition.getConditionExpression();

        int conditionSetSize = alertDefinition.getConditions().size();
        if (expression == BooleanExpression.ANY) {
            log.debug("Need only 1 of " + conditionSetSize + " conditions to be true, " + "found "
                + unmatchedLogs.size() + " for " + alertDefinition);
            return (unmatchedLogs.size() > 0);
        } else if (expression == BooleanExpression.ALL) {
            log.debug("Need all " + conditionSetSize + " conditions to be true, " + "found " + unmatchedLogs.size()
                + " for " + alertDefinition);
            return (unmatchedLogs.size() == conditionSetSize);
        } else {
            log.error("AlertConditionLogManager does not support " + expression + " boolean expression yet");
            return false;
        }
    }
}