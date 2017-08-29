/*
 * *** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * J4Care.
 * Portions created by the Initial Developer are Copyright (C) 2017
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * *** END LICENSE BLOCK *****
 */

package org.dcm4chee.arc.audit;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4chee.arc.conf.ArchiveDeviceExtension;
import org.dcm4chee.arc.retrieve.HttpServletRequestInfo;
import org.dcm4chee.arc.retrieve.InstanceLocations;
import org.dcm4chee.arc.retrieve.RetrieveContext;
import java.util.*;

class RetrieveContextAuditInfoBuilder {

    private final RetrieveContext ctx;
    private final ArchiveDeviceExtension arcDev;
    private final HttpServletRequestInfo httpServletRequestInfo;
    private BuildAuditInfo[][] buildAuditInfos = new BuildAuditInfo[2][];
    private AuditServiceUtils.EventType eventType = AuditServiceUtils.EventType.RTRV___TRF;

    RetrieveContextAuditInfoBuilder(RetrieveContext ctx, ArchiveDeviceExtension arcDev, AuditServiceUtils.EventType et) {
        this.ctx = ctx;
        this.arcDev = arcDev;
        httpServletRequestInfo = ctx.getHttpServletRequestInfo();
        eventType = et;
        processRetrieve();
    }

    private void processRetrieve() {
        if (someInstancesRetrieveFailed())
            processPartialRetrieve();
        else
            buildAuditInfos[0] = buildAuditInfos(toBuildAuditInfo(true), ctx.getMatches());
    }

    private void processPartialRetrieve() {
        HashSet<InstanceLocations> failed = new HashSet<>();
        HashSet<InstanceLocations> success = new HashSet<>();
        List<String> failedList = Arrays.asList(ctx.failedSOPInstanceUIDs());
        Collection<InstanceLocations> instanceLocations = ctx.getMatches();
        success.addAll(instanceLocations);
        for (InstanceLocations instanceLocation : instanceLocations) {
            if (failedList.contains(instanceLocation.getSopInstanceUID())) {
                failed.add(instanceLocation);
                success.remove(instanceLocation);
            }
        }
        buildAuditInfos[0] = buildAuditInfos(toBuildAuditInfo(true), failed);
        buildAuditInfos[1] = buildAuditInfos(toBuildAuditInfo(false), success);
    }

    private BuildAuditInfo[] buildAuditInfos(BuildAuditInfo buildAuditInfo, Collection<InstanceLocations> il) {
        LinkedHashSet<BuildAuditInfo> objs = new LinkedHashSet<>();
        objs.add(buildAuditInfo);
        objs.addAll(buildInstanceInfos(ctx.getCStoreForwards()));
        objs.addAll(buildInstanceInfos(il));
        return objs.toArray(new BuildAuditInfo[objs.size()]);
    }

    private LinkedHashSet<BuildAuditInfo> buildInstanceInfos(Collection<InstanceLocations> il) {
        LinkedHashSet<BuildAuditInfo> objs = new LinkedHashSet<>();
        for (InstanceLocations instanceLocation : il) {
            Attributes attrs = instanceLocation.getAttributes();
            BuildAuditInfo iI = new BuildAuditInfo.Builder()
                    .studyUIDAccNumDate(attrs)
                    .sopCUID(attrs.getString(Tag.SOPClassUID))
                    .sopIUID(attrs.getString(Tag.SOPInstanceUID))
                    .pIDAndName(attrs, arcDev)
                    .build();
            objs.add(iI);
        }
        return objs;
    }

    private BuildAuditInfo toBuildAuditInfo(boolean checkForFailures) {
        boolean failedIUIDShow = isFailedIUIDShow(checkForFailures);
        String outcome = checkForFailures ? buildOutcome() : null;

        return isExportTriggered(ctx)
                ? httpServletRequestInfo != null
                    ? restfulTriggeredExport(failedIUIDShow, outcome)
                    : schedulerTriggeredExport(failedIUIDShow, outcome)
                : httpServletRequestInfo != null
                    ? wadoRS(failedIUIDShow, outcome)
                    : cMoveCGet(failedIUIDShow, outcome);
    }

    private BuildAuditInfo cMoveCGet(boolean failedIUIDShow, String outcome) {
        return new BuildAuditInfo.Builder()
            .calledAET(ctx.getLocalAETitle())
            .destAET(ctx.getDestinationAETitle())
            .destNapID(ctx.getDestinationHostName())
            .warning(buildWarning())
            .callingHost(ctx.getRequestorHostName())
            .moveAET(ctx.getMoveOriginatorAETitle())
            .outcome(outcome)
            .failedIUIDShow(failedIUIDShow)
            .build();
    }

    private BuildAuditInfo wadoRS(boolean failedIUIDShow, String outcome) {
        return new BuildAuditInfo.Builder()
            .calledAET(httpServletRequestInfo.requestURI)
            .destAET(httpServletRequestInfo.requesterUserID)
            .destNapID(ctx.getDestinationHostName())
            .warning(buildWarning())
            .callingHost(ctx.getRequestorHostName())
            .outcome(outcome)
            .failedIUIDShow(failedIUIDShow)
            .build();
    }

    private BuildAuditInfo schedulerTriggeredExport(boolean failedIUIDShow, String outcome) {
        return new BuildAuditInfo.Builder()
            .calledAET(ctx.getLocalAETitle())
            .destAET(ctx.getDestinationAETitle())
            .destNapID(ctx.getDestinationHostName())
            .warning(buildWarning())
            .callingHost(ctx.getRequestorHostName())
            .outcome(outcome)
            .failedIUIDShow(failedIUIDShow)
            .isExport()
            .build();
    }

    private BuildAuditInfo restfulTriggeredExport(boolean failedIUIDShow, String outcome) {
        return new BuildAuditInfo.Builder()
            .callingAET(httpServletRequestInfo.requesterUserID)
            .callingHost(ctx.getRequestorHostName())
            .calledAET(httpServletRequestInfo.requestURI)
            .destAET(ctx.getDestinationAETitle())
            .destNapID(ctx.getDestinationHostName())
            .warning(buildWarning())
            .outcome(outcome)
            .failedIUIDShow(failedIUIDShow)
            .isExport()
            .build();
    }

    private boolean isFailedIUIDShow(boolean checkForFailures) {
        return checkForFailures && (allInstancesRetrieveFailed() || someInstancesRetrieveFailed());
    }

    private boolean isExportTriggered(RetrieveContext ctx) {
        return (ctx.getRequestAssociation() == null && ctx.getStoreAssociation() != null)
                || (ctx.getRequestAssociation() == null && ctx.getStoreAssociation() == null && ctx.getException() != null);
    }

    private boolean someInstancesRetrieveFailed() {
        return ctx.failedSOPInstanceUIDs().length != ctx.getMatches().size() && ctx.failedSOPInstanceUIDs().length > 0;
    }

    private boolean allInstancesRetrieveCompleted() {
        return (ctx.failedSOPInstanceUIDs().length == 0 && !ctx.getMatches().isEmpty())
                || (ctx.getMatches().isEmpty() && !ctx.getCStoreForwards().isEmpty());
    }

    private boolean allInstancesRetrieveFailed() {
        return ctx.failedSOPInstanceUIDs().length == ctx.getMatches().size() && !ctx.getMatches().isEmpty();
    }

    private String buildWarning() {
        return allInstancesRetrieveCompleted() && ctx.warning() != 0
                ? ctx.warning() == ctx.getMatches().size()
                    ? "Warnings on retrieve of all instances"
                    : "Warnings on retrieve of " + ctx.warning() + " instances"
                : null;
    }

    private String buildOutcome() {
        return ctx.getException() != null
                ? ctx.getException().getMessage() != null
                    ? ctx.getException().getMessage()
                    : ctx.getException().toString()
                : allInstancesRetrieveFailed()
                    ? "Unable to perform sub-operations on all instances"
                    : someInstancesRetrieveFailed()
                        ? "Retrieve of " + ctx.failed() + " objects failed"
                        : null;
    }

    BuildAuditInfo[][] getBuildAuditInfos() {
        return buildAuditInfos;
    }

    AuditServiceUtils.EventType getEventType() {
        return eventType;
    }
}