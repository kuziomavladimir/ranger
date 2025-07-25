/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.authorization.hive.authorizer;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.hive.common.FileUtils;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.HiveObjectRef;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.security.HiveAuthenticationProvider;
import org.apache.hadoop.hive.ql.security.authorization.AuthorizationUtils;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveAccessControlException;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveAuthzContext;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveAuthzPluginException;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveAuthzSessionContext;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveMetastoreClientFactory;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveOperationType;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HivePolicyProvider;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HivePrincipal;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HivePrivilege;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HivePrivilegeInfo;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HivePrivilegeObject;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HivePrivilegeObject.HivePrivObjectActionType;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HivePrivilegeObject.HivePrivilegeObjectType;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveResourceACLs.AccessResult;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveResourceACLs.Privilege;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveRoleGrant;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.thirdparty.com.google.common.collect.Sets;
import org.apache.ranger.authorization.hadoop.constants.RangerHadoopConstants;
import org.apache.ranger.authorization.utils.StringUtil;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerRole;
import org.apache.ranger.plugin.model.RangerRole.RoleMember;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.model.RangerServiceDef.RangerDataMaskTypeDef;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.policyengine.RangerAccessRequestImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.apache.ranger.plugin.policyengine.RangerPolicyEngine;
import org.apache.ranger.plugin.policyengine.RangerResourceACLs;
import org.apache.ranger.plugin.policyevaluator.RangerPolicyEvaluator;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.apache.ranger.plugin.util.GrantRevokeRequest;
import org.apache.ranger.plugin.util.GrantRevokeRoleRequest;
import org.apache.ranger.plugin.util.RangerAccessRequestUtil;
import org.apache.ranger.plugin.util.RangerPerfTracer;
import org.apache.ranger.plugin.util.RangerRequestedResources;
import org.apache.ranger.plugin.util.RangerRoles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RangerHiveAuthorizer extends RangerHiveAuthorizerBase {
    private static final Logger LOG                       = LoggerFactory.getLogger(RangerHiveAuthorizer.class);
    private static final Logger PERF_HIVEAUTH_REQUEST_LOG = RangerPerfTracer.getPerfLogger("hiveauth.request");

    private static final char        COLUMN_SEP                    = ',';
    private static final String      HIVE_CONF_VAR_QUERY_STRING    = "hive.query.string";
    private static final String      DEFAULT_RANGER_POLICY_GRANTOR = "ranger";
    private static final String      ROLE_ALL                      = "ALL";
    private static final String      ROLE_DEFAULT                  = "DEFAULT";
    private static final String      ROLE_NONE                     = "NONE";
    private static final String      ROLE_ADMIN                    = "admin";
    private static final String      CMD_CREATE_ROLE               = "create role %s";
    private static final String      CMD_DROP_ROLE                 = "drop role %s";
    private static final String      CMD_SHOW_ROLES                = "show roles";
    private static final String      CMD_SHOW_ROLE_GRANT           = "show role grant %s";
    private static final String      CMD_SHOW_PRINCIPALS           = "show principals %s";
    private static final String      CMD_GRANT_ROLE                = "grant %s to %s ";
    private static final String      CMD_REVOKE_ROLE               = "revoke %s from %s";
    private static final String      CMD_SET_ROLE                  = "set role %s";
    private static final Set<String> RESERVED_ROLE_NAMES;

    private static volatile RangerHivePlugin hivePlugin;

    private String      currentUserName;
    private Set<String> currentRoles;
    private String      adminRole;
    private boolean     isCurrentRoleSet;

    public RangerHiveAuthorizer(HiveMetastoreClientFactory metastoreClientFactory, HiveConf hiveConf, HiveAuthenticationProvider hiveAuthenticator, HiveAuthzSessionContext sessionContext) {
        super(metastoreClientFactory, hiveConf, hiveAuthenticator, sessionContext);

        LOG.debug("RangerHiveAuthorizer.RangerHiveAuthorizer()");

        RangerHivePlugin plugin = hivePlugin;

        if (plugin == null) {
            synchronized (RangerHiveAuthorizer.class) {
                plugin = hivePlugin;

                if (plugin == null) {
                    String appType = "unknown";

                    if (sessionContext != null) {
                        switch (sessionContext.getClientType()) {
                            case HIVECLI:
                                appType = "hiveCLI";
                                break;

                            case HIVESERVER2:
                                appType = "hiveServer2";
                                break;

                            case HIVEMETASTORE:
                                appType = "hiveMetastore";
                                break;

                            case OTHER:
                                appType = "other";
                                break;

                        }
                    }

                    plugin = new RangerHivePlugin(appType);

                    plugin.init();

                    hivePlugin = plugin;
                }
            }
        }
    }

    static void setOwnerUser(RangerHiveResource resource, HivePrivilegeObject hiveObj, IMetaStoreClient metaStoreClient, Map<String, String> objOwners) {
        if (hiveObj != null) {
            String objName = null;
            String owner   = hiveObj.getOwnerName();

            // resource.setOwnerUser(hiveObj.getOwnerName());
            switch (hiveObj.getType()) {
                case DATABASE:
                    try {
                        objName = hiveObj.getDbname();
                        if (StringUtils.isBlank(owner) && objOwners != null) {
                            owner = objOwners.get(objName);
                        }

                        if (StringUtils.isBlank(owner)) {
                            Database database = metaStoreClient != null ? metaStoreClient.getDatabase(hiveObj.getDbname()) : null;

                            if (database != null) {
                                owner = database.getOwnerName();
                            }
                        } else {
                            LOG.debug("Owner for database {} is already known", objName);
                        }
                    } catch (Exception excp) {
                        LOG.error("failed to get database object from Hive metastore. dbName={}", hiveObj.getDbname(), excp);
                    }
                    break;

                case TABLE_OR_VIEW:
                case COLUMN:
                    try {
                        objName = hiveObj.getDbname() + "." + hiveObj.getObjectName();
                        if (StringUtils.isBlank(owner) && objOwners != null) {
                            owner = objOwners.get(objName);
                        }

                        if (StringUtils.isBlank(owner)) {
                            Table table = metaStoreClient != null ? metaStoreClient.getTable(hiveObj.getDbname(), hiveObj.getObjectName()) : null;

                            if (table != null) {
                                owner = table.getOwner();
                            }
                        } else {
                            LOG.debug("Owner for table {} is already known", objName);
                        }
                    } catch (Exception excp) {
                        LOG.error("failed to get table object from Hive metastore. dbName={}, tblName={}", hiveObj.getDbname(), hiveObj.getObjectName(), excp);
                    }
                    break;
            }

            if (objOwners != null && objName != null) {
                objOwners.put(objName, owner);
            }

            if (StringUtils.isNotBlank(objName) && StringUtils.isNotBlank(owner)) {
                resource.setOwnerUser(owner);
            }
        }

        LOG.debug("setOwnerUser({}): ownerName={}", hiveObj, resource.getOwnerUser());
    }

    /**
     * Grant privileges for principals on the object
     *
     * @param hivePrincipals
     * @param hivePrivileges
     * @param hivePrivObject
     * @param grantorPrincipal
     * @param grantOption
     * @throws HiveAuthzPluginException
     * @throws HiveAccessControlException
     */
    @Override
    public void grantPrivileges(List<HivePrincipal> hivePrincipals, List<HivePrivilege> hivePrivileges, HivePrivilegeObject hivePrivObject, HivePrincipal grantorPrincipal, boolean grantOption) throws HiveAuthzPluginException, HiveAccessControlException {
        LOG.debug("grantPrivileges() => HivePrivilegeObject:{}grantorPrincipal: {}hivePrincipals{}hivePrivileges{}", toString(hivePrivObject, new StringBuilder()), grantorPrincipal, hivePrincipals, hivePrivileges);

        if (!RangerHivePlugin.updateXaPoliciesOnGrantRevoke) {
            throw new HiveAuthzPluginException("GRANT/REVOKE not supported in Ranger HiveAuthorizer. Please use Ranger Security Admin to setup access control.");
        }

        RangerHiveAuditHandler auditHandler = new RangerHiveAuditHandler(hivePlugin.getConfig());

        try {
            List<HivePrivilegeObject> outputs  = new ArrayList<>(Collections.singletonList(hivePrivObject));
            RangerHiveResource        resource = getHiveResource(HiveOperationType.GRANT_PRIVILEGE, hivePrivObject, null, outputs, null);
            GrantRevokeRequest        request  = createGrantRevokeData(resource, hivePrincipals, hivePrivileges, grantorPrincipal, grantOption);

            LOG.debug("grantPrivileges(): {}", request);

            hivePlugin.grantAccess(request, auditHandler);
        } catch (Exception excp) {
            throw new HiveAccessControlException(excp);
        } finally {
            auditHandler.flushAudit();
        }
    }

    /**
     * Revoke privileges for principals on the object
     *
     * @param hivePrincipals
     * @param hivePrivileges
     * @param hivePrivObject
     * @param grantorPrincipal
     * @param grantOption
     * @throws HiveAuthzPluginException
     * @throws HiveAccessControlException
     */
    @Override
    public void revokePrivileges(List<HivePrincipal> hivePrincipals, List<HivePrivilege> hivePrivileges, HivePrivilegeObject hivePrivObject, HivePrincipal grantorPrincipal, boolean grantOption) throws HiveAuthzPluginException, HiveAccessControlException {
        if (!RangerHivePlugin.updateXaPoliciesOnGrantRevoke) {
            throw new HiveAuthzPluginException("GRANT/REVOKE not supported in Ranger HiveAuthorizer. Please use Ranger Security Admin to setup access control.");
        }

        RangerHiveAuditHandler auditHandler = new RangerHiveAuditHandler(hivePlugin.getConfig());

        try {
            List<HivePrivilegeObject> outputs  = new ArrayList<>(Collections.singletonList(hivePrivObject));
            RangerHiveResource        resource = getHiveResource(HiveOperationType.REVOKE_PRIVILEGE, hivePrivObject, null, outputs, null);
            GrantRevokeRequest        request  = createGrantRevokeData(resource, hivePrincipals, hivePrivileges, grantorPrincipal, grantOption);

            LOG.debug("revokePrivileges(): {}", request);

            hivePlugin.revokeAccess(request, auditHandler);
        } catch (Exception excp) {
            throw new HiveAccessControlException(excp);
        } finally {
            auditHandler.flushAudit();
        }
    }

    @Override
    public void createRole(String roleName, HivePrincipal adminGrantor) throws HiveAuthzPluginException, HiveAccessControlException {
        LOG.debug(" ==> RangerHiveAuthorizer.createRole()");

        RangerHiveAuditHandler auditHandler    = new RangerHiveAuditHandler(hivePlugin.getConfig());
        String                 currentUserName = getGrantorUsername(adminGrantor);
        List<String>           roleNames       = Collections.singletonList(roleName);
        List<String>           userNames       = Collections.singletonList(currentUserName);
        boolean                result          = false;

        if (RESERVED_ROLE_NAMES.contains(roleName.trim().toUpperCase())) {
            throw new HiveAuthzPluginException("Role name cannot be one of the reserved roles: " + RESERVED_ROLE_NAMES);
        }

        try {
            RangerRole role = new RangerRole();

            role.setName(roleName);
            role.setCreatedByUser(currentUserName);
            role.setCreatedBy(currentUserName);
            role.setUpdatedBy(currentUserName);

            //Add grantor as the member to this role with grant option.
            RoleMember       userMember     = new RoleMember(currentUserName, true);
            List<RoleMember> userMemberList = new ArrayList<>();

            userMemberList.add(userMember);
            role.setUsers(userMemberList);

            RangerRole ret = hivePlugin.createRole(role, auditHandler);

            LOG.debug("<== createRole(): {}", ret);

            result = true;
        } catch (Exception excp) {
            throw new HiveAccessControlException(excp);
        } finally {
            RangerAccessResult accessResult = createAuditEvent(hivePlugin, currentUserName, userNames, HiveOperationType.CREATEROLE, HiveAccessType.CREATE, roleNames, result);

            auditHandler.processResult(accessResult);
            auditHandler.flushAudit();
        }
    }

    @Override
    public void dropRole(String roleName) throws HiveAuthzPluginException, HiveAccessControlException {
        LOG.debug("RangerHiveAuthorizer.dropRole()");

        RangerHiveAuditHandler auditHandler = new RangerHiveAuditHandler(hivePlugin.getConfig());
        UserGroupInformation   ugi          = getCurrentUserGroupInfo();
        boolean                result       = false;
        List<String>           roleNames    = Collections.singletonList(roleName);

        if (ugi == null) {
            throw new HiveAccessControlException("Permission denied: user information not available");
        }

        if (RESERVED_ROLE_NAMES.contains(roleName.trim().toUpperCase())) {
            throw new HiveAuthzPluginException("Role name cannot be one of the reserved roles: " + RESERVED_ROLE_NAMES);
        }

        String       currentUserName = ugi.getShortUserName();
        List<String> userNames       = Collections.singletonList(currentUserName);

        try {
            LOG.debug("<== dropRole(): {}", roleName);

            hivePlugin.dropRole(currentUserName, roleName, auditHandler);

            result = true;
        } catch (Exception excp) {
            throw new HiveAccessControlException(excp);
        } finally {
            RangerAccessResult accessResult = createAuditEvent(hivePlugin, currentUserName, userNames, HiveOperationType.DROPROLE, HiveAccessType.DROP, roleNames, result);

            auditHandler.processResult(accessResult);
            auditHandler.flushAudit();
        }
    }

    @Override
    public List<HiveRoleGrant> getPrincipalGrantInfoForRole(String roleName) throws HiveAuthzPluginException, HiveAccessControlException {
        LOG.debug("==> RangerHiveAuthorizer.getPrincipalGrantInfoForRole() for RoleName: {}", roleName);

        List<HiveRoleGrant>    ret          = new ArrayList<>();
        List<String>           roleNames    = Collections.singletonList(roleName);
        List<String>           userNames    = null;
        RangerHiveAuditHandler auditHandler = new RangerHiveAuditHandler(hivePlugin.getConfig());
        boolean                result       = false;

        if (hivePlugin == null) {
            throw new HiveAuthzPluginException("RangerHiveAuthorizer.getPrincipalGrantInfoForRole(): HivePlugin initialization failed...");
        }

        UserGroupInformation ugi = getCurrentUserGroupInfo();

        if (ugi == null) {
            throw new HiveAccessControlException("RangerHiveAuthorizer.getPrincipalGrantInfoForRole(): User information not available...");
        }

        String currentUserName = ugi.getShortUserName();

        try {
            if (!hivePlugin.isServiceAdmin(currentUserName)) {
                throw new HiveAccessControlException("Permission denied: User not authorized to perform this operation!");
            }

            userNames = Collections.singletonList(currentUserName);

            if (StringUtils.isNotEmpty(roleName)) {
                RangerRole rangerRole = getRangerRoleForRoleName(roleName);

                if (rangerRole != null) {
                    for (RoleMember roleMember : rangerRole.getRoles()) {
                        HiveRoleGrant hiveRoleGrant = getHiveRoleGrant(rangerRole, roleMember, HivePrincipal.HivePrincipalType.ROLE.name());

                        ret.add(hiveRoleGrant);
                    }

                    for (RoleMember group : rangerRole.getGroups()) {
                        HiveRoleGrant hiveRoleGrant = getHiveRoleGrant(rangerRole, group, HivePrincipal.HivePrincipalType.GROUP.name());

                        ret.add(hiveRoleGrant);
                    }

                    for (RoleMember user : rangerRole.getUsers()) {
                        HiveRoleGrant hiveRoleGrant = getHiveRoleGrant(rangerRole, user, HivePrincipal.HivePrincipalType.USER.name());

                        ret.add(hiveRoleGrant);
                    }

                    result = true;
                }
            }
        } catch (Exception excp) {
            throw new HiveAuthzPluginException(excp);
        } finally {
            RangerAccessResult accessResult = createAuditEvent(hivePlugin, currentUserName, userNames, HiveOperationType.SHOW_ROLE_PRINCIPALS, HiveAccessType.SELECT, roleNames, result);

            hivePlugin.evalAuditPolicies(accessResult);

            auditHandler.processResult(accessResult);
            auditHandler.flushAudit();
        }

        LOG.debug("<== RangerHiveAuthorizer.getPrincipalGrantInfoForRole() for Rolename: {} Roles: {}", roleName, ret);

        return ret;
    }

    @Override
    public List<HiveRoleGrant> getRoleGrantInfoForPrincipal(HivePrincipal principal) throws HiveAuthzPluginException, HiveAccessControlException {
        LOG.debug("==> RangerHiveAuthorizer.getRoleGrantInfoForPrincipal() for Principal: {}", principal);

        List<HiveRoleGrant>    ret           = new ArrayList<>();
        List<String>           principalInfo = null;
        List<String>           userNames     = null;
        RangerHiveAuditHandler auditHandler  = new RangerHiveAuditHandler(hivePlugin.getConfig());
        boolean                result        = false;

        if (hivePlugin == null) {
            throw new HiveAuthzPluginException("RangerHiveAuthorizer.getRoleGrantInfoForPrincipal(): HivePlugin initialization failed...");
        }

        UserGroupInformation ugi = getCurrentUserGroupInfo();

        if (ugi == null) {
            throw new HiveAccessControlException("RangerHiveAuthorizer.getRoleGrantInfoForPrincipal(): User information not available...");
        }

        String currentUserName = ugi.getShortUserName();

        try {
            String principalName = principal.getName();
            String type          = principal.getType().name();

            userNames     = Collections.singletonList(currentUserName);
            principalInfo = Collections.singletonList(principal.getType() + " " + principalName);

            if (!hivePlugin.isServiceAdmin(currentUserName) && !principalName.equals(currentUserName)) {
                throw new HiveAccessControlException("Permission denied: user information not available");
            }

            Set<RangerRole> roles = hivePlugin.getRangerRoleForPrincipal(principalName, type);

            if (CollectionUtils.isNotEmpty(roles)) {
                for (RangerRole rangerRole : roles) {
                    switch (type) {
                        case "USER":
                            RoleMember userRoleMember = new RoleMember(principalName, false);

                            ret.add(getHiveRoleGrant(rangerRole, userRoleMember, type));
                            break;
                        case "GROUP":
                            RoleMember groupRoleMember = new RoleMember(principalName, false);

                            ret.add(getHiveRoleGrant(rangerRole, groupRoleMember, type));
                            break;
                        case "ROLE":
                            RoleMember roleRoleMember = new RoleMember(principalName, false);

                            ret.add(getHiveRoleGrant(rangerRole, roleRoleMember, type));
                            break;
                    }
                }

                result = true;
            }
        } catch (Exception excp) {
            throw new HiveAuthzPluginException(excp);
        } finally {
            RangerAccessResult accessResult = createAuditEvent(hivePlugin, currentUserName, userNames, HiveOperationType.SHOW_ROLE_GRANT, HiveAccessType.SELECT, principalInfo, result);

            hivePlugin.evalAuditPolicies(accessResult);

            auditHandler.processResult(accessResult);
            auditHandler.flushAudit();
        }

        LOG.debug("<== getRoleGrantInfoForPrincipal(): Principal: {} Roles: {}", principal, ret);

        return ret;
    }

    @Override
    public void grantRole(List<HivePrincipal> hivePrincipals, List<String> roles, boolean grantOption, HivePrincipal grantorPrinc) throws HiveAccessControlException {
        LOG.debug("RangerHiveAuthorizerBase.grantRole()");

        boolean                result       = false;
        RangerHiveAuditHandler auditHandler = new RangerHiveAuditHandler(hivePlugin.getConfig());
        String                 username     = getGrantorUsername(grantorPrinc);
        List<String>           principals   = new ArrayList<>();

        try {
            GrantRevokeRoleRequest request   = new GrantRevokeRoleRequest();
            Set<String>            userList  = new HashSet<>();
            Set<String>            roleList  = new HashSet<>();
            Set<String>            groupList = new HashSet<>();

            request.setGrantor(username);
            request.setGrantorGroups(getGrantorGroupNames(grantorPrinc));

            for (HivePrincipal principal : hivePrincipals) {
                String name;

                switch (principal.getType()) {
                    case USER:
                        name = principal.getName();

                        userList.add(name);
                        principals.add("USER " + name);
                        break;

                    case GROUP:
                        name = principal.getName();

                        groupList.add(name);
                        principals.add("GROUP " + name);
                        break;

                    case ROLE:
                        name = principal.getName();

                        roleList.add(name);
                        principals.add("ROLE " + name);
                        break;

                    case UNKNOWN:
                        break;
                }
            }

            request.setUsers(userList);
            request.setGroups(groupList);
            request.setRoles(roleList);
            request.setGrantOption(grantOption);
            request.setTargetRoles(new HashSet<>(roles));

            SessionState ss = SessionState.get();

            if (ss != null) {
                request.setClientIPAddress(ss.getUserIpAddress());
                request.setSessionId(ss.getSessionId());

                HiveConf hiveConf = ss.getConf();

                if (hiveConf != null) {
                    request.setRequestData(hiveConf.get(HIVE_CONF_VAR_QUERY_STRING));
                }
            }

            HiveAuthzSessionContext sessionContext = getHiveAuthzSessionContext();

            if (sessionContext != null) {
                request.setClientType(sessionContext.getClientType() == null ? null : sessionContext.getClientType().toString());
            }

            hivePlugin.grantRole(request, auditHandler);

            result = true;
        } catch (Exception excp) {
            throw new HiveAccessControlException(excp);
        } finally {
            RangerAccessResult accessResult = createAuditEvent(hivePlugin, username, principals, HiveOperationType.GRANT_ROLE, HiveAccessType.ALTER, roles, result);

            auditHandler.processResult(accessResult);
            auditHandler.flushAudit();
        }
    }

    @Override
    public void revokeRole(List<HivePrincipal> hivePrincipals, List<String> roles, boolean grantOption, HivePrincipal grantorPrinc) throws HiveAccessControlException {
        LOG.debug("RangerHiveAuthorizerBase.revokeRole()");

        boolean                result          = false;
        RangerHiveAuditHandler auditHandler    = new RangerHiveAuditHandler(hivePlugin.getConfig());
        String                 grantorUserName = getGrantorUsername(grantorPrinc);
        List<String>           principals      = new ArrayList<>();

        try {
            GrantRevokeRoleRequest request   = new GrantRevokeRoleRequest();
            Set<String>            userList  = new HashSet<>();
            Set<String>            roleList  = new HashSet<>();
            Set<String>            groupList = new HashSet<>();

            request.setGrantor(grantorUserName);
            request.setGrantorGroups(getGrantorGroupNames(grantorPrinc));

            for (HivePrincipal principal : hivePrincipals) {
                String principalName;

                switch (principal.getType()) {
                    case USER:
                        principalName = principal.getName();

                        userList.add(principalName);
                        principals.add("USER " + principalName);
                        break;

                    case GROUP:
                        principalName = principal.getName();

                        groupList.add(principalName);
                        principals.add("GROUP " + principalName);
                        break;
                    case ROLE:
                        principalName = principal.getName();

                        roleList.add(principalName);
                        principals.add("ROLE " + principalName);
                        break;

                    case UNKNOWN:
                        break;
                }
            }

            request.setUsers(userList);
            request.setGroups(groupList);
            request.setRoles(roleList);
            request.setGrantOption(grantOption);
            request.setTargetRoles(new HashSet<>(roles));

            SessionState ss = SessionState.get();

            if (ss != null) {
                request.setClientIPAddress(ss.getUserIpAddress());
                request.setSessionId(ss.getSessionId());

                HiveConf hiveConf = ss.getConf();

                if (hiveConf != null) {
                    request.setRequestData(hiveConf.get(HIVE_CONF_VAR_QUERY_STRING));
                }
            }

            HiveAuthzSessionContext sessionContext = getHiveAuthzSessionContext();

            if (sessionContext != null) {
                request.setClientType(sessionContext.getClientType() == null ? null : sessionContext.getClientType().toString());
            }

            LOG.debug("revokeRole(): {}", request);

            hivePlugin.revokeRole(request, auditHandler);

            result = true;
        } catch (Exception excp) {
            throw new HiveAccessControlException(excp);
        } finally {
            RangerAccessResult accessResult = createAuditEvent(hivePlugin, grantorUserName, principals, HiveOperationType.REVOKE_ROLE, HiveAccessType.ALTER, roles, result);

            auditHandler.processResult(accessResult);
            auditHandler.flushAudit();
        }
    }

    /**
     * Check if user has privileges to do this action on these objects
     *
     * @param hiveOpType
     * @param inputHObjs
     * @param outputHObjs
     * @param context
     * @throws HiveAccessControlException
     */
    @Override
    public void checkPrivileges(HiveOperationType hiveOpType, List<HivePrivilegeObject> inputHObjs, List<HivePrivilegeObject> outputHObjs, HiveAuthzContext context) throws HiveAccessControlException {
        UserGroupInformation ugi = getCurrentUserGroupInfo();

        if (ugi == null) {
            throw new HiveAccessControlException("Permission denied: user information not available");
        }

        RangerHiveAuditHandler auditHandler = new RangerHiveAuditHandler(hivePlugin.getConfig());
        RangerPerfTracer       perf         = null;

        try {
            HiveAuthzSessionContext sessionContext = getHiveAuthzSessionContext();
            String                  user           = ugi.getShortUserName();
            Set<String>             groups         = Sets.newHashSet(ugi.getGroupNames());
            Set<String>             roles          = getCurrentRolesForUser(user, groups);
            Map<String, String>     objOwners      = new HashMap<>();

            if (LOG.isDebugEnabled()) {
                LOG.debug(toString(hiveOpType, inputHObjs, outputHObjs, context, sessionContext));
            }

            if (hiveOpType == HiveOperationType.DFS) {
                handleDfsCommand(hiveOpType, inputHObjs, user, auditHandler);

                return;
            }

            if (RangerPerfTracer.isPerfTraceEnabled(PERF_HIVEAUTH_REQUEST_LOG)) {
                perf = RangerPerfTracer.getPerfTracer(PERF_HIVEAUTH_REQUEST_LOG, "RangerHiveAuthorizer.checkPrivileges(hiveOpType=" + hiveOpType + ")");
            }

            List<RangerHiveAccessRequest> requests = new ArrayList<>();

            if (!CollectionUtils.isEmpty(inputHObjs)) {
                for (HivePrivilegeObject hiveObj : inputHObjs) {
                    RangerHiveResource resource = getHiveResource(hiveOpType, hiveObj, inputHObjs, outputHObjs, objOwners);

                    if (resource == null) { // possible if input object/object is of a kind that we don't currently authorize
                        continue;
                    }

                    String         pathStr     = hiveObj.getObjectName();
                    HiveObjectType hiveObjType = resource.getObjectType();

                    if (hiveObjType == HiveObjectType.URI && isPathInFSScheme(pathStr)) {
                        FsAction   permission = getURIAccessType(hiveOpType);
                        Path       path       = new Path(pathStr);
                        FileSystem fs;

                        try {
                            fs = FileSystem.get(path.toUri(), getHiveConf());
                        } catch (IOException e) {
                            LOG.error("Error getting permissions for {}", path, e);

                            throw new HiveAccessControlException(String.format("Permission denied: user [%s] does not have [%s] privilege on [%s]", user, permission.name(), path), e);
                        }

                        boolean shouldCheckAccess = true;

                        if (isMountedFs(fs)) {
                            Path resolvedPath = resolvePath(path, fs);

                            if (resolvedPath != null) {
                                // we know the resolved path scheme. Let's check the resolved path
                                // scheme is part of hivePlugin.getFSScheme.
                                shouldCheckAccess = isPathInFSScheme(resolvedPath.toUri().toString());
                            }
                        }

                        if (shouldCheckAccess) {
                            if (!isURIAccessAllowed(user, permission, path, fs)) {
                                throw new HiveAccessControlException(String.format("Permission denied: user [%s] does not have [%s] privilege on [%s]", user, permission.name(), path));
                            }

                            continue;
                        }
                        // This means we got resolved path scheme is not part of
                        // hivePlugin.getFSScheme
                    }

                    HiveAccessType accessType = getAccessType(hiveObj, hiveOpType, hiveObjType, true);

                    if (accessType == HiveAccessType.NONE) {
                        continue;
                    }

                    if (!existsByResourceAndAccessType(requests, resource, accessType)) {
                        RangerHiveAccessRequest request = new RangerHiveAccessRequest(resource, user, groups, roles, hiveOpType, accessType, context, sessionContext);

                        requests.add(request);
                    }
                }
            } else {
                // this should happen only for SHOWDATABASES
                if (hiveOpType == HiveOperationType.SHOWDATABASES) {
                    RangerHiveResource      resource = new RangerHiveResource(HiveObjectType.DATABASE, null);
                    RangerHiveAccessRequest request  = new RangerHiveAccessRequest(resource, user, groups, roles, hiveOpType.name(), HiveAccessType.USE, context, sessionContext);

                    requests.add(request);
                } else if (hiveOpType == HiveOperationType.SHOW_GRANT) {
                    String  command                  = context.getCommandString();
                    String  regexForShowGrantCommand = "SHOW GRANT\\s*(\\w+)?\\s*(\\w+)?\\s*ON\\s*(\\w+)?\\s*(\\S+)";
                    Pattern pattern                  = Pattern.compile(regexForShowGrantCommand, Pattern.CASE_INSENSITIVE);
                    Matcher matcher                  = pattern.matcher(command);

                    if (matcher.find()) {
                        String hiveObjectType  = matcher.group(3);
                        String hiveObjectValue = matcher.group(4);
                        String dbName          = hiveObjectValue;
                        String tableName       = "";

                        if (hiveObjectValue.contains(".")) {
                            String[] parts = hiveObjectValue.split("\\.");

                            dbName    = parts[0];
                            tableName = parts[1];
                        }

                        if (hiveObjectType.toUpperCase().equals(HiveObjectType.DATABASE.name())) {
                            RangerHiveResource      resource = new RangerHiveResource(HiveObjectType.DATABASE, dbName);
                            RangerHiveAccessRequest request  = new RangerHiveAccessRequest(resource, user, groups, roles, hiveOpType.name(), HiveAccessType.USE, context, sessionContext);

                            requests.add(request);
                        } else if (hiveObjectType.toUpperCase().equals(HiveObjectType.TABLE.name())) {
                            RangerHiveResource      resource = new RangerHiveResource(HiveObjectType.TABLE, dbName, tableName);
                            RangerHiveAccessRequest request  = new RangerHiveAccessRequest(resource, user, groups, roles, hiveOpType.name(), HiveAccessType.USE, context, sessionContext);

                            requests.add(request);
                        }
                    }
                } else if (hiveOpType == HiveOperationType.REPLDUMP) {
                    // This happens when REPL DUMP command with null inputHObjs is sent in checkPrivileges()
                    // following parsing is done for Audit info
                    RangerHiveResource resource;
                    HiveObj            hiveObj   = new HiveObj(context);
                    String             dbName    = hiveObj.getDatabaseName();
                    String             tableName = hiveObj.getTableName();

                    LOG.debug("Database: {} Table: {}", dbName, tableName);

                    if (!StringUtil.isEmpty(tableName)) {
                        resource = new RangerHiveResource(HiveObjectType.TABLE, dbName, tableName);
                    } else {
                        resource = new RangerHiveResource(HiveObjectType.DATABASE, dbName, null);
                    }

                    RangerHiveAccessRequest request = new RangerHiveAccessRequest(resource, user, groups, roles, hiveOpType.name(), HiveAccessType.REPLADMIN, context, sessionContext);

                    requests.add(request);
                } else if (hiveOpType.equals(HiveOperationType.ALTERTABLE_OWNER)) {
                    RangerHiveAccessRequest request = buildRequestForAlterTableSetOwnerFromCommandString(user, groups, roles, hiveOpType.name(), context, sessionContext);

                    if (request != null) {
                        requests.add(request);
                    } else {
                        throw new HiveAccessControlException(String.format("Permission denied: user [%s] does not have privilege for [%s] command", user, hiveOpType.name()));
                    }
                } else {
                    LOG.debug("RangerHiveAuthorizer.checkPrivileges: Unexpected operation type[{}] received with empty input objects list!", hiveOpType);
                }
            }

            if (!CollectionUtils.isEmpty(outputHObjs)) {
                for (HivePrivilegeObject hiveObj : outputHObjs) {
                    RangerHiveResource resource = getHiveResource(hiveOpType, hiveObj, inputHObjs, outputHObjs, objOwners);

                    if (resource == null) { // possible if input object/object is of a kind that we don't currently authorize
                        continue;
                    }

                    String         pathStr     = hiveObj.getObjectName();
                    HiveObjectType hiveObjType = resource.getObjectType();

                    if (hiveObjType == HiveObjectType.URI && isPathInFSScheme(pathStr)) {
                        FsAction   permission = getURIAccessType(hiveOpType);
                        Path       path       = new Path(pathStr);
                        FileSystem fs;

                        try {
                            fs = FileSystem.get(path.toUri(), getHiveConf());
                        } catch (IOException e) {
                            LOG.error("Error getting permissions for {}", path, e);

                            throw new HiveAccessControlException(String.format("Permission denied: user [%s] does not have [%s] privilege on [%s]", user, permission.name(), path), e);
                        }

                        boolean shouldCheckAccess = true;

                        if (isMountedFs(fs)) {
                            Path resolvedPath = resolvePath(path, fs);

                            if (resolvedPath != null) {
                                // we know the resolved path scheme. Let's check the resolved path
                                // scheme is part of hivePlugin.getFSScheme.
                                shouldCheckAccess = isPathInFSScheme(resolvedPath.toUri().toString());
                            }
                        }

                        if (shouldCheckAccess) {
                            if (!isURIAccessAllowed(user, permission, path, fs)) {
                                throw new HiveAccessControlException(String.format("Permission denied: user [%s] does not have [%s] privilege on [%s]", user, permission.name(), path));
                            }

                            continue;
                        }
                        // This means we got resolved path scheme is not part of
                        // hivePlugin.getFSScheme
                    }

                    HiveAccessType accessType = getAccessType(hiveObj, hiveOpType, hiveObjType, false);

                    if (accessType == HiveAccessType.NONE) {
                        continue;
                    }

                    if (!existsByResourceAndAccessType(requests, resource, accessType)) {
                        RangerHiveAccessRequest request = new RangerHiveAccessRequest(resource, user, groups, roles, hiveOpType, accessType, context, sessionContext);

                        requests.add(request);
                    }
                }
            } else {
                if (hiveOpType == HiveOperationType.REPLLOAD) {
                    // This happens when REPL LOAD command with null inputHObjs is sent in checkPrivileges()
                    // following parsing is done for Audit info
                    RangerHiveResource resource;
                    HiveObj            hiveObj   = new HiveObj(context);
                    String             dbName    = hiveObj.getDatabaseName();
                    String             tableName = hiveObj.getTableName();

                    LOG.debug("Database: {} Table: {}", dbName, tableName);

                    if (!StringUtil.isEmpty(tableName)) {
                        resource = new RangerHiveResource(HiveObjectType.TABLE, dbName, tableName);
                    } else {
                        resource = new RangerHiveResource(HiveObjectType.DATABASE, dbName, null);
                    }

                    RangerHiveAccessRequest request = new RangerHiveAccessRequest(resource, user, groups, roles, hiveOpType.name(), HiveAccessType.REPLADMIN, context, sessionContext);

                    requests.add(request);
                }
            }

            buildRequestContextWithAllAccessedResources(requests);

            for (RangerHiveAccessRequest request : requests) {
                LOG.debug("request: {}", request);

                RangerHiveResource resource = (RangerHiveResource) request.getResource();
                RangerAccessResult result   = null;

                if (resource.getObjectType() == HiveObjectType.COLUMN && StringUtils.contains(resource.getColumn(), COLUMN_SEP)) {
                    List<RangerAccessRequest> colRequests = new ArrayList<>();
                    String[]                  columns     = StringUtils.split(resource.getColumn(), COLUMN_SEP);

                    // in case of multiple columns, original request is not sent to the plugin; hence service-def will not be set
                    resource.setServiceDef(hivePlugin.getServiceDef());

                    for (String column : columns) {
                        if (column != null) {
                            column = column.trim();
                        }

                        if (StringUtils.isBlank(column)) {
                            continue;
                        }

                        RangerHiveResource colResource = new RangerHiveResource(HiveObjectType.COLUMN, resource.getDatabase(), resource.getTable(), column);

                        colResource.setOwnerUser(resource.getOwnerUser());

                        RangerHiveAccessRequest colRequest = request.copy();

                        colRequest.setResource(colResource);

                        colRequests.add(colRequest);
                    }

                    Collection<RangerAccessResult> colResults = hivePlugin.isAccessAllowed(colRequests, auditHandler);

                    if (colResults != null) {
                        for (RangerAccessResult colResult : colResults) {
                            result = colResult;

                            if (result != null && !result.getIsAllowed()) {
                                break;
                            }
                        }
                    }
                } else {
                    result = hivePlugin.isAccessAllowed(request, auditHandler);
                }

                if ((result == null || result.getIsAllowed()) && isBlockAccessIfRowfilterColumnMaskSpecified(hiveOpType, request)) {
                    // check if row-filtering is applicable for the table/view being accessed
                    HiveAccessType     savedAccessType = request.getHiveAccessType();
                    RangerHiveResource tblResource     = new RangerHiveResource(HiveObjectType.TABLE, resource.getDatabase(), resource.getTable());

                    request.setHiveAccessType(HiveAccessType.SELECT); // filtering/masking policies are defined only for SELECT
                    request.setResource(tblResource);

                    RangerAccessResult rowFilterResult = getRowFilterResult(request);

                    if (isRowFilterEnabled(rowFilterResult)) {
                        if (result == null) {
                            result = new RangerAccessResult(RangerPolicy.POLICY_TYPE_ACCESS, rowFilterResult.getServiceName(), rowFilterResult.getServiceDef(), request);
                        }

                        result.setIsAllowed(false);
                        result.setPolicyId(rowFilterResult.getPolicyId());
                        result.setPolicyVersion(rowFilterResult.getPolicyVersion());
                        result.setReason("User does not have access to all rows of the table");
                    } else {
                        // check if masking is enabled for any column in the table/view
                        request.setResourceMatchingScope(RangerAccessRequest.ResourceMatchingScope.SELF_OR_DESCENDANTS);

                        RangerAccessResult dataMaskResult = getDataMaskResult(request);

                        if (isDataMaskEnabled(dataMaskResult)) {
                            if (result == null) {
                                result = new RangerAccessResult(RangerPolicy.POLICY_TYPE_ACCESS, dataMaskResult.getServiceName(), dataMaskResult.getServiceDef(), request);
                            }

                            result.setIsAllowed(false);
                            result.setPolicyId(dataMaskResult.getPolicyId());
                            result.setPolicyVersion(dataMaskResult.getPolicyVersion());
                            result.setReason("User does not have access to unmasked column values");
                        }
                    }

                    request.setHiveAccessType(savedAccessType);
                    request.setResource(resource);

                    if (result != null && !result.getIsAllowed()) {
                        auditHandler.processResult(result);
                    }
                }

                if (result == null || !result.getIsAllowed()) {
                    String path = resource.getAsString();

                    path = (path == null) ? "Unknown resource!!" : buildPathForException(path, hiveOpType);

                    throw new HiveAccessControlException(String.format("Permission denied: user [%s] does not have [%s] privilege on [%s]", user, request.getHiveAccessType().name(), path));
                }
            }
        } finally {
            auditHandler.flushAudit();
            RangerPerfTracer.log(perf);
        }
    }

    /**
     * Check if user has privileges to do this action on these objects
     *
     * @param objs
     * @param context
     * @throws HiveAuthzPluginException
     * @throws HiveAccessControlException
     */
    // Commented out to avoid build errors until this interface is stable in Hive Branch
    // @Override
    public List<HivePrivilegeObject> filterListCmdObjects(List<HivePrivilegeObject> objs, HiveAuthzContext context) {
        LOG.debug("==> filterListCmdObjects({}, {})", objs, context);

        RangerPerfTracer       perf         = null;
        RangerHiveAuditHandler auditHandler = new RangerHiveAuditHandler(hivePlugin.getConfig());

        if (RangerPerfTracer.isPerfTraceEnabled(PERF_HIVEAUTH_REQUEST_LOG)) {
            perf = RangerPerfTracer.getPerfTracer(PERF_HIVEAUTH_REQUEST_LOG, "RangerHiveAuthorizer.filterListCmdObjects()");
        }

        List<HivePrivilegeObject> ret = null;

        // bail out early if nothing is there to validate!
        if (objs == null) {
            LOG.debug("filterListCmdObjects: meta objects list was null!");
        } else if (objs.isEmpty()) {
            LOG.debug("filterListCmdObjects: meta objects list was empty!");

            ret = objs;
        } else if (getCurrentUserGroupInfo() == null) {
            /*
             * This is null for metastore and there doesn't seem to be a way to tell if one is running as metastore or hiveserver2!
             */
            LOG.warn("filterListCmdObjects: user information not available");

            ret = objs;
        } else {
            LOG.debug("filterListCmdObjects: number of input objects[{}]", objs.size());

            // get user/group info
            UserGroupInformation    ugi            = getCurrentUserGroupInfo(); // we know this can't be null since we checked it above!
            HiveAuthzSessionContext sessionContext = getHiveAuthzSessionContext();
            String                  user           = ugi.getShortUserName();
            Set<String>             groups         = Sets.newHashSet(ugi.getGroupNames());
            Set<String>             roles          = getCurrentRolesForUser(user, groups);
            Map<String, String>     objOwners      = new HashMap<>();

            LOG.debug("filterListCmdObjects: user[{}], groups[{}], roles[{}] ", user, groups, roles);

            if (ret == null) { // if we got any items to filter then we can't return back a null.  We must return back a list even if its empty.
                ret = new ArrayList<>(objs.size());
            }

            for (HivePrivilegeObject privilegeObject : objs) {
                if (LOG.isDebugEnabled()) {
                    HivePrivObjectActionType actionType    = privilegeObject.getActionType();
                    HivePrivilegeObjectType  objectType    = privilegeObject.getType();
                    String                   objectName    = privilegeObject.getObjectName();
                    String                   dbName        = privilegeObject.getDbname();
                    List<String>             columns       = privilegeObject.getColumns();
                    List<String>             partitionKeys = privilegeObject.getPartKeys();
                    String                   commandString = context == null ? null : context.getCommandString();
                    String                   ipAddress     = context == null ? null : context.getIpAddress();

                    LOG.debug("filterListCmdObjects: actionType[{}], objectType[{}], objectName[{}], dbName[{}], columns[{}], partitionKeys[{}]; context: commandString[{}], ipAddress[{}]", actionType, objectType, objectName, dbName, columns, partitionKeys, commandString, ipAddress);
                }

                RangerHiveResource resource = createHiveResourceForFiltering(privilegeObject, objOwners);

                if (resource == null) {
                    LOG.error("filterListCmdObjects: RangerHiveResource returned by createHiveResource is null");
                } else {
                    RangerHiveAccessRequest request = new RangerHiveAccessRequest(resource, user, groups, roles, context, sessionContext);
                    RangerAccessResult      result  = hivePlugin.isAccessAllowed(request, auditHandler);

                    if (result == null) {
                        LOG.error("filterListCmdObjects: Internal error: null RangerAccessResult object received back from isAccessAllowed()!");
                    } else if (!result.getIsAllowed()) {
                        if (LOG.isDebugEnabled()) {
                            String path = resource.getAsString();

                            LOG.debug("filterListCmdObjects: Permission denied: user [{}] does not have [{}] privilege on [{}]. resource[{}], request[{}], result[{}]", user, request.getHiveAccessType().name(), path, resource, request, result);
                        }
                    } else {
                        LOG.debug("filterListCmdObjects: access allowed. resource[{}], request[{}], result[{}]", resource, request, result);

                        ret.add(privilegeObject);
                    }
                }
            }
        }

        auditHandler.flushAudit();

        RangerPerfTracer.log(perf);

        LOG.debug("<== filterListCmdObjects: count[{}], ret[{}]", ret == null ? 0 : ret.size(), ret);

        return ret;
    }

    @Override
    public List<String> getAllRoles() throws HiveAuthzPluginException, HiveAccessControlException {
        LOG.debug("==> RangerHiveAuthorizer.getAllRoles()");

        List<String>           ret          = new ArrayList<>();
        RangerHiveAuditHandler auditHandler = new RangerHiveAuditHandler(hivePlugin.getConfig());
        List<String>           userNames    = null;
        boolean                result       = false;

        if (hivePlugin == null) {
            throw new HiveAuthzPluginException("RangerHiveAuthorizer.getAllRoles(): HivePlugin initialization failed...");
        }

        UserGroupInformation ugi = getCurrentUserGroupInfo();

        if (ugi == null) {
            throw new HiveAccessControlException("RangerHiveAuthorizer.getAllRoles(): User information not available...");
        }

        String currentUserName = ugi.getShortUserName();

        try {
            if (!hivePlugin.isServiceAdmin(currentUserName)) {
                throw new HiveAccessControlException("RangerHiveAuthorizer.getAllRoles(): User not authorized to run show roles...");
            }

            userNames = Collections.singletonList(currentUserName);

            RangerRoles rangerRoles = hivePlugin.getRangerRoles();
            if (rangerRoles != null) {
                Set<RangerRole> roles = rangerRoles.getRangerRoles();

                if (CollectionUtils.isNotEmpty(roles)) {
                    for (RangerRole rangerRole : roles) {
                        ret.add(rangerRole.getName());
                    }
                }
            }

            result = true;
        } catch (Exception excp) {
            throw new HiveAuthzPluginException(excp);
        } finally {
            RangerAccessResult accessResult = createAuditEvent(hivePlugin, currentUserName, userNames, HiveOperationType.SHOW_ROLES, HiveAccessType.SELECT, null, result);

            hivePlugin.evalAuditPolicies(accessResult);
            auditHandler.processResult(accessResult);
            auditHandler.flushAudit();
        }

        LOG.debug("<== RangerHiveAuthorizer.getAllRoles() roles: {}", ret);

        return ret;
    }

    @Override
    public void setCurrentRole(String roleName) throws HiveAccessControlException, HiveAuthzPluginException {
        // from SQLStdHiveAccessController.setCurrentRole()
        RangerHiveAuditHandler auditHandler = new RangerHiveAuditHandler(hivePlugin.getConfig());
        List<String>           roles        = new ArrayList<>();
        boolean                result       = false;

        roles.add(roleName);

        try {
            initUserRoles();

            if (ROLE_NONE.equalsIgnoreCase(roleName)) {
                // for set role NONE, clear all roles for current session.
                currentRoles.clear();

                isCurrentRoleSet = true;
                result           = true;

                return;
            }

            if (ROLE_ALL.equalsIgnoreCase(roleName)) {
                // for set role ALL, reset roles to default roles.
                currentRoles.clear();
                currentRoles.addAll(getCurrentRoleNamesFromRanger());

                isCurrentRoleSet = true;
                result           = true;

                return;
            }

            for (String role : getCurrentRoleNamesFromRanger()) {
                // set to one of the roles user belongs to.
                if (role.equalsIgnoreCase(roleName)) {
                    currentRoles.clear();
                    currentRoles.add(role);

                    isCurrentRoleSet = true;
                    result           = true;

                    return;
                }
            }

            // set to ADMIN role, if user belongs there.
            if (ROLE_ADMIN.equalsIgnoreCase(roleName) && null != this.adminRole) {
                currentRoles.clear();
                currentRoles.add(adminRole);

                isCurrentRoleSet = true;
                result           = true;

                return;
            }

            LOG.info("Current user : {}, Current Roles : {}", currentUserName, currentRoles);

            // If we are here it means, user is requesting a role he doesn't belong to.
            throw new HiveAccessControlException(currentUserName + " doesn't belong to role " + roleName);
        } catch (Exception excp) {
            throw new HiveAuthzPluginException(excp);
        } finally {
            List<String> roleUsers = new ArrayList<>();

            roleUsers.add(currentUserName);

            RangerAccessResult accessResult = createAuditEvent(hivePlugin, currentUserName, roleUsers, HiveOperationType.SET, HiveAccessType.UPDATE, roles, result);

            hivePlugin.evalAuditPolicies(accessResult);
            auditHandler.processResult(accessResult);
            auditHandler.flushAudit();
        }
    }

    @Override
    public List<String> getCurrentRoleNames() throws HiveAuthzPluginException {
        LOG.debug("RangerHiveAuthorizer.getCurrentRoleNames()");

        UserGroupInformation ugi = getCurrentUserGroupInfo();

        if (ugi == null) {
            throw new HiveAuthzPluginException("User information not available");
        }

        boolean                result       = false;
        List<String>           ret          = new ArrayList<>();
        String                 user         = ugi.getShortUserName();
        List<String>           userNames    = Collections.singletonList(user);
        RangerHiveAuditHandler auditHandler = new RangerHiveAuditHandler(hivePlugin.getConfig());

        try {
            LOG.debug("<== getCurrentRoleNames() for user {}", user);

            for (String role : getCurrentRoles()) {
                ret.add(role);
            }

            result = true;
        } catch (Exception excp) {
            throw new HiveAuthzPluginException(excp);
        } finally {
            RangerAccessResult accessResult = createAuditEvent(hivePlugin, user, userNames, HiveOperationType.SHOW_ROLES, HiveAccessType.SELECT, ret, result);

            auditHandler.processResult(accessResult);
            auditHandler.flushAudit();
        }

        return ret;
    }

    @Override
    public List<HivePrivilegeObject> applyRowFilterAndColumnMasking(HiveAuthzContext queryContext, List<HivePrivilegeObject> hiveObjs) throws SemanticException {
        LOG.debug("==> applyRowFilterAndColumnMasking({}, objCount={})", queryContext, hiveObjs.size());

        List<HivePrivilegeObject> ret  = new ArrayList<>();
        RangerPerfTracer          perf = null;

        if (RangerPerfTracer.isPerfTraceEnabled(PERF_HIVEAUTH_REQUEST_LOG)) {
            perf = RangerPerfTracer.getPerfTracer(PERF_HIVEAUTH_REQUEST_LOG, "RangerHiveAuthorizer.applyRowFilterAndColumnMasking()");
        }

        if (CollectionUtils.isNotEmpty(hiveObjs)) {
            IMetaStoreClient    metaStoreClient = getMetaStoreClient();
            Map<String, String> objOwners       = new HashMap<>();

            for (HivePrivilegeObject hiveObj : hiveObjs) {
                HivePrivilegeObjectType hiveObjType = hiveObj.getType();

                if (hiveObjType == null) {
                    hiveObjType = HivePrivilegeObjectType.TABLE_OR_VIEW;
                }

                LOG.debug("applyRowFilterAndColumnMasking(hiveObjType={})", hiveObjType);

                boolean needToTransform = false;

                if (hiveObjType == HivePrivilegeObjectType.TABLE_OR_VIEW) {
                    String database = hiveObj.getDbname();
                    String table    = hiveObj.getObjectName();

                    String rowFilterExpr = getRowFilterExpression(queryContext, hiveObj, metaStoreClient, objOwners);

                    if (StringUtils.isNotBlank(rowFilterExpr)) {
                        LOG.debug("rowFilter(database={}, table={}): {}", database, table, rowFilterExpr);

                        hiveObj.setRowFilterExpression(rowFilterExpr);

                        needToTransform = true;
                    }

                    if (CollectionUtils.isNotEmpty(hiveObj.getColumns())) {
                        List<String> columnTransformers = new ArrayList<>();

                        for (String column : hiveObj.getColumns()) {
                            boolean isColumnTransformed = addCellValueTransformerAndCheckIfTransformed(queryContext, hiveObj, column, columnTransformers, metaStoreClient, objOwners);

                            LOG.debug("addCellValueTransformerAndCheckIfTransformed(database={}, table={}, column={}): {}", database, table, column, isColumnTransformed);

                            needToTransform = needToTransform || isColumnTransformed;
                        }

                        hiveObj.setCellValueTransformers(columnTransformers);
                    }
                }

                if (needToTransform) {
                    ret.add(hiveObj);
                }
            }
        }

        RangerPerfTracer.log(perf);

        LOG.debug("<== applyRowFilterAndColumnMasking({}, objCount={}): retCount={}", queryContext, hiveObjs.size(), ret.size());

        return ret;
    }

    @Override
    public boolean needTransform() {
        return true; // TODO: derive from the policies
    }

    @Override
    public List<HivePrivilegeInfo> showPrivileges(HivePrincipal principal, HivePrivilegeObject privObj) throws HiveAuthzPluginException {
        List<HivePrivilegeInfo> ret;

        LOG.debug("==> RangerHiveAuthorizer.showPrivileges ==>  principal: {}HivePrivilegeObject : {}", principal, privObj.getObjectName());

        if (hivePlugin == null) {
            throw new HiveAuthzPluginException("RangerHiveAuthorizer.showPrivileges error: hivePlugin is null");
        }

        try {
            HiveObjectRef msObjRef = AuthorizationUtils.getThriftHiveObjectRef(privObj);

            if (msObjRef.getDbName() == null) {
                throw new HiveAuthzPluginException("RangerHiveAuthorizer.showPrivileges() only supports SHOW PRIVILEGES for Hive resources and not user level");
            }

            ret = getHivePrivilegeInfos(principal, privObj);
        } catch (Exception e) {
            LOG.error("RangerHiveAuthorizer.showPrivileges() error", e);

            throw new HiveAuthzPluginException("RangerHiveAuthorizer.showPrivileges() error: " + e.getMessage(), e);
        }

        LOG.debug("<== RangerHiveAuthorizer.showPrivileges() Result: {}", ret);

        return ret;
    }

    @Override
    public HivePolicyProvider getHivePolicyProvider() throws HiveAuthzPluginException {
        if (hivePlugin == null) {
            throw new HiveAuthzPluginException();
        }

        return new RangerHivePolicyProvider(hivePlugin, this);
    }

    RangerHiveResource createHiveResource(HivePrivilegeObject privilegeObject) {
        return createHiveResource(privilegeObject, null);
    }

    RangerHiveResource createHiveResource(HivePrivilegeObject privilegeObject, Map<String, String> objOwners) {
        RangerHiveResource      resource   = null;
        HivePrivilegeObjectType objectType = privilegeObject.getType();
        String                  objectName = privilegeObject.getObjectName();
        String                  dbName     = privilegeObject.getDbname();

        switch (objectType) {
            case DATABASE:
                resource = new RangerHiveResource(HiveObjectType.DATABASE, dbName);
                break;
            case TABLE_OR_VIEW:
                resource = new RangerHiveResource(HiveObjectType.TABLE, dbName, objectName);
                break;
            case COLUMN:
                List<String> columns = privilegeObject.getColumns();
                int numOfColumns = columns == null ? 0 : columns.size();
                if (numOfColumns == 1) {
                    resource = new RangerHiveResource(HiveObjectType.COLUMN, dbName, objectName, columns.get(0));
                } else {
                    LOG.warn("RangerHiveAuthorizer.getHiveResource: unexpected number of columns requested:{}, objectType:{}", numOfColumns, objectType);
                }
                break;
            default:
                LOG.warn("RangerHiveAuthorizer.getHiveResource: unexpected objectType:{}", objectType);
        }

        if (resource != null) {
            setOwnerUser(resource, privilegeObject, getMetaStoreClient(), objOwners);

            resource.setServiceDef(hivePlugin == null ? null : hivePlugin.getServiceDef());
        }

        return resource;
    }

    private RangerAccessResult getDataMaskResult(RangerHiveAccessRequest request) {
        LOG.debug("==> getDataMaskResult(request={})", request);

        RangerAccessResult ret = hivePlugin.evalDataMaskPolicies(request, null);

        LOG.debug("<== getDataMaskResult(request={}): ret={}", request, ret);

        return ret;
    }

    private RangerAccessResult getRowFilterResult(RangerHiveAccessRequest request) {
        LOG.debug("==> getRowFilterResult(request={})", request);

        RangerAccessResult ret = hivePlugin.evalRowFilterPolicies(request, null);

        LOG.debug("<== getRowFilterResult(request={}): ret={}", request, ret);

        return ret;
    }

    private boolean isDataMaskEnabled(RangerAccessResult result) {
        return result != null && result.isMaskEnabled();
    }

    private boolean isRowFilterEnabled(RangerAccessResult result) {
        return result != null && result.isRowFilterEnabled() && StringUtils.isNotEmpty(result.getFilterExpr());
    }

    private String getRowFilterExpression(HiveAuthzContext context, HivePrivilegeObject tableOrView, IMetaStoreClient metaStoreClient, Map<String, String> objOwners) throws SemanticException {
        UserGroupInformation ugi = getCurrentUserGroupInfo();

        if (ugi == null) {
            throw new SemanticException("user information not available");
        }

        String databaseName    = tableOrView.getDbname();
        String tableOrViewName = tableOrView.getObjectName();

        LOG.debug("==> getRowFilterExpression({}, {})", databaseName, tableOrViewName);

        String ret = null;

        RangerHiveAuditHandler auditHandler = new RangerHiveAuditHandler(hivePlugin.getConfig());

        try {
            HiveAuthzSessionContext sessionContext = getHiveAuthzSessionContext();
            String                  user           = ugi.getShortUserName();
            Set<String>             groups         = Sets.newHashSet(ugi.getGroupNames());
            Set<String>             roles          = getCurrentRolesForUser(user, groups);
            HiveObjectType          objectType     = HiveObjectType.TABLE;
            RangerHiveResource      resource       = new RangerHiveResource(objectType, databaseName, tableOrViewName);

            setOwnerUser(resource, tableOrView, metaStoreClient, objOwners);

            RangerHiveAccessRequest request = new RangerHiveAccessRequest(resource, user, groups, roles, objectType.name(), HiveAccessType.SELECT, context, sessionContext);
            RangerAccessResult      result  = hivePlugin.evalRowFilterPolicies(request, auditHandler);

            if (isRowFilterEnabled(result)) {
                ret = result.getFilterExpr();
            }
        } finally {
            auditHandler.flushAudit();
        }

        LOG.debug("<== getRowFilterExpression({}, {}): {}", databaseName, tableOrViewName, ret);

        return ret;
    }

    private boolean addCellValueTransformerAndCheckIfTransformed(HiveAuthzContext context, HivePrivilegeObject tableOrView, String columnName, List<String> columnTransformers, IMetaStoreClient metaStoreClient, Map<String, String> objOwners) throws SemanticException {
        UserGroupInformation ugi = getCurrentUserGroupInfo();

        if (ugi == null) {
            throw new SemanticException("user information not available");
        }

        String databaseName    = tableOrView.getDbname();
        String tableOrViewName = tableOrView.getObjectName();

        LOG.debug("==> addCellValueTransformerAndCheckIfTransformed({}, {}, {})", databaseName, tableOrViewName, columnName);

        boolean ret;
        String  columnTransformer = columnName;

        RangerHiveAuditHandler auditHandler = new RangerHiveAuditHandler(hivePlugin.getConfig());

        try {
            HiveAuthzSessionContext sessionContext = getHiveAuthzSessionContext();
            String                  user           = ugi.getShortUserName();
            Set<String>             groups         = Sets.newHashSet(ugi.getGroupNames());
            Set<String>             roles          = getCurrentRolesForUser(user, groups);
            HiveObjectType          objectType     = HiveObjectType.COLUMN;
            RangerHiveResource      resource       = new RangerHiveResource(objectType, databaseName, tableOrViewName, columnName);

            setOwnerUser(resource, tableOrView, metaStoreClient, objOwners);

            RangerHiveAccessRequest request = new RangerHiveAccessRequest(resource, user, groups, roles, objectType.name(), HiveAccessType.SELECT, context, sessionContext);
            RangerAccessResult      result  = hivePlugin.evalDataMaskPolicies(request, auditHandler);

            ret = isDataMaskEnabled(result);

            if (ret) {
                String                maskType    = result.getMaskType();
                RangerDataMaskTypeDef maskTypeDef = result.getMaskTypeDef();
                String                transformer = null;
                if (maskTypeDef != null) {
                    transformer = maskTypeDef.getTransformer();
                }

                if (StringUtils.equalsIgnoreCase(maskType, RangerPolicy.MASK_TYPE_NULL)) {
                    columnTransformer = "NULL";
                } else if (StringUtils.equalsIgnoreCase(maskType, RangerPolicy.MASK_TYPE_CUSTOM)) {
                    String maskedValue = result.getMaskedValue();

                    if (maskedValue == null) {
                        columnTransformer = "NULL";
                    } else {
                        columnTransformer = maskedValue.replace("{col}", columnName);
                    }
                } else if (StringUtils.isNotEmpty(transformer)) {
                    columnTransformer = transformer.replace("{col}", columnName);
                }

                if (columnTransformer.contains("{colType}")) {
                    String colType = getColumnType(tableOrView, columnName, metaStoreClient);

                    if (StringUtils.isBlank(colType)) {
                        LOG.warn("addCellValueTransformerAndCheckIfTransformed({}, {}, {}): failed to find column datatype", databaseName, tableOrViewName, columnName);

                        colType = "string";
                    }

                    columnTransformer = columnTransformer.replace("{colType}", colType);
                }
            }
        } finally {
            auditHandler.flushAudit();
        }

        columnTransformers.add(columnTransformer);

        LOG.debug("<== addCellValueTransformerAndCheckIfTransformed({}, {}, {}): {}", databaseName, tableOrViewName, columnName, ret);

        return ret;
    }

    private RangerHiveResource createHiveResourceForFiltering(HivePrivilegeObject privilegeObject, Map<String, String> objOwners) {
        RangerHiveResource      resource   = null;
        HivePrivilegeObjectType objectType = privilegeObject.getType();

        switch (objectType) {
            case DATABASE:
            case TABLE_OR_VIEW:
                resource = createHiveResource(privilegeObject, objOwners);
                break;
            default:
                LOG.warn("RangerHiveAuthorizer.createHiveResourceForFiltering: unexpected objectType:{}", objectType);
        }

        return resource;
    }

    private RangerHiveResource getHiveResource(HiveOperationType hiveOpType, HivePrivilegeObject hiveObj, List<HivePrivilegeObject> inputs, List<HivePrivilegeObject> outputs, Map<String, String> objOwners) {
        RangerHiveResource ret        = null;
        HiveObjectType     objectType = getObjectType(hiveObj, hiveOpType);

        switch (objectType) {
            case DATABASE:
                ret = new RangerHiveResource(objectType, hiveObj.getDbname());

                if (!isCreateOperation(hiveOpType)) {
                    setOwnerUser(ret, hiveObj, getMetaStoreClient(), objOwners);
                }
                break;

            case TABLE:
            case VIEW:
            case FUNCTION:
                ret = new RangerHiveResource(objectType, hiveObj.getDbname(), hiveObj.getObjectName());

                // To suppress PMD violations
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Size of inputs = [{}, Size of outputs = [{}]", CollectionUtils.isNotEmpty(inputs) ? inputs.size() : 0, CollectionUtils.isNotEmpty(outputs) ? outputs.size() : 0);
                }

                setOwnerUser(ret, hiveObj, getMetaStoreClient(), objOwners);

                if (isCreateOperation(hiveOpType)) {
                    HivePrivilegeObject dbObject = getDatabaseObject(hiveObj.getDbname(), inputs, outputs);

                    if (dbObject != null) {
                        setOwnerUser(ret, dbObject, getMetaStoreClient(), objOwners);
                    }
                }

                break;

            case PARTITION:
            case INDEX:
                ret = new RangerHiveResource(objectType, hiveObj.getDbname(), hiveObj.getObjectName());
                break;

            case COLUMN:
                ret = new RangerHiveResource(objectType, hiveObj.getDbname(), hiveObj.getObjectName(), StringUtils.join(hiveObj.getColumns(), COLUMN_SEP));

                setOwnerUser(ret, hiveObj, getMetaStoreClient(), objOwners);
                break;

            case URI:
            case SERVICE_NAME:
                ret = new RangerHiveResource(objectType, hiveObj.getObjectName());
                break;

            case GLOBAL:
                ret = new RangerHiveResource(objectType, hiveObj.getObjectName());
                break;

            case NONE:
                break;
        }

        if (ret != null) {
            ret.setServiceDef(hivePlugin == null ? null : hivePlugin.getServiceDef());
        }

        return ret;
    }

    private boolean isCreateOperation(HiveOperationType hiveOpType) {
        boolean ret = false;
        switch (hiveOpType) {
            case CREATETABLE:
            case CREATEVIEW:
            case CREATETABLE_AS_SELECT:
            case CREATE_MATERIALIZED_VIEW:
            case CREATEFUNCTION:
                ret = true;
                break;
        }
        return ret;
    }

    private HivePrivilegeObject getDatabaseObject(String dbName, List<HivePrivilegeObject> inputs, List<HivePrivilegeObject> outputs) {
        HivePrivilegeObject ret = null;

        if (CollectionUtils.isNotEmpty(outputs)) {
            for (HivePrivilegeObject hiveOutPrivObj : outputs) {
                if (hiveOutPrivObj.getType() == HivePrivilegeObjectType.DATABASE && dbName.equalsIgnoreCase(hiveOutPrivObj.getDbname())) {
                    ret = hiveOutPrivObj;
                }
            }
        }

        if (ret == null && CollectionUtils.isNotEmpty(inputs)) {
            for (HivePrivilegeObject hiveInPrivObj : inputs) {
                if (hiveInPrivObj.getType() == HivePrivilegeObjectType.DATABASE && dbName.equalsIgnoreCase(hiveInPrivObj.getDbname())) {
                    ret = hiveInPrivObj;
                }
            }
        }

        return ret;
    }

    private HiveObjectType getObjectType(HivePrivilegeObject hiveObj, HiveOperationType hiveOpType) {
        HiveObjectType objType        = HiveObjectType.NONE;
        String         hiveOpTypeName = hiveOpType.name().toLowerCase();

        if (hiveObj.getType() == null) {
            return HiveObjectType.DATABASE;
        }

        switch (hiveObj.getType()) {
            case DATABASE:
                objType = HiveObjectType.DATABASE;
                break;

            case PARTITION:
                objType = HiveObjectType.PARTITION;
                break;

            case TABLE_OR_VIEW:
                if (hiveOpTypeName.contains("index")) {
                    objType = HiveObjectType.INDEX;
                } else if (!StringUtil.isEmpty(hiveObj.getColumns())) {
                    objType = HiveObjectType.COLUMN;
                } else if (hiveOpTypeName.contains("view")) {
                    objType = HiveObjectType.VIEW;
                } else {
                    objType = HiveObjectType.TABLE;
                }
                break;

            case FUNCTION:
                objType = HiveObjectType.FUNCTION;

                if (isTempUDFOperation(hiveOpTypeName, hiveObj)) {
                    objType = HiveObjectType.GLOBAL;
                }
                break;

            case DFS_URI:
            case LOCAL_URI:
                objType = HiveObjectType.URI;
                break;

            case COMMAND_PARAMS:
            case GLOBAL:
                if ("add".equals(hiveOpTypeName) || "compile".equals(hiveOpTypeName)) {
                    objType = HiveObjectType.GLOBAL;
                }
                break;

            case SERVICE_NAME:
                objType = HiveObjectType.SERVICE_NAME;
                break;

            case COLUMN:
                // Thejas: this value is unused in Hive; the case should not be hit.
                break;
        }

        return objType;
    }

    private HiveAccessType getAccessType(HivePrivilegeObject hiveObj, HiveOperationType hiveOpType, HiveObjectType hiveObjectType, boolean isInput) {
        HiveAccessType           accessType       = HiveAccessType.NONE;
        HivePrivObjectActionType objectActionType = hiveObj.getActionType();

        // This is for S3 read operation
        if (hiveObjectType == HiveObjectType.URI && isInput) {
            accessType = HiveAccessType.READ;

            return accessType;
        }

        // This is for S3 write
        if (hiveObjectType == HiveObjectType.URI && !isInput) {
            accessType = HiveAccessType.WRITE;

            return accessType;
        }

        switch (objectActionType) {
            case INSERT:
            case INSERT_OVERWRITE:
            case UPDATE:
            case DELETE:
                accessType = HiveAccessType.UPDATE;
                break;
            case OTHER:
                switch (hiveOpType) {
                    case CREATEDATABASE:
                        if (hiveObj.getType() == HivePrivilegeObjectType.DATABASE) {
                            accessType = HiveAccessType.CREATE;
                        }
                        break;
                    case CREATEDATACONNECTOR:
                        if (hiveObj.getType() == HivePrivilegeObjectType.DATACONNECTOR) {
                            accessType = HiveAccessType.CREATE;
                        }
                        break;
                    case CREATEFUNCTION:
                        if (hiveObj.getType() == HivePrivilegeObjectType.FUNCTION) {
                            accessType = HiveAccessType.CREATE;
                        }
                        if (hiveObjectType == HiveObjectType.GLOBAL) {
                            accessType = HiveAccessType.TEMPUDFADMIN;
                        }
                        break;

                    case CREATETABLE:
                    case CREATEVIEW:
                    case CREATETABLE_AS_SELECT:
                    case CREATE_MATERIALIZED_VIEW:
                        if (hiveObj.getType() == HivePrivilegeObjectType.TABLE_OR_VIEW) {
                            accessType = isInput ? HiveAccessType.SELECT : HiveAccessType.CREATE;
                        }
                        break;
                    case ALTERVIEW_AS:
                        if (hiveObj.getType() == HivePrivilegeObjectType.TABLE_OR_VIEW) {
                            accessType = isInput ? HiveAccessType.SELECT : HiveAccessType.ALTER;
                        } else if (hiveObj.getType() == HivePrivilegeObjectType.DATABASE) {
                            accessType = HiveAccessType.SELECT;
                        }
                        break;
                    case ALTERDATABASE:
                    case ALTERDATABASE_LOCATION:
                    case ALTERDATABASE_OWNER:
                        // Refer - HIVE-21968
                    case ALTERPARTITION_BUCKETNUM:
                    case ALTERPARTITION_FILEFORMAT:
                    case ALTERPARTITION_LOCATION:
                    case ALTERPARTITION_MERGEFILES:
                    case ALTERPARTITION_PROTECTMODE:
                    case ALTERPARTITION_SERDEPROPERTIES:
                    case ALTERPARTITION_SERIALIZER:
                    case ALTERTABLE_ADDCOLS:
                    case ALTERTABLE_ADDPARTS:
                    case ALTERTABLE_ARCHIVE:
                    case ALTERTABLE_BUCKETNUM:
                    case ALTERTABLE_CLUSTER_SORT:
                    case ALTERTABLE_COMPACT:
                    case ALTERTABLE_DROPPARTS:
                    case ALTERTABLE_DROPCONSTRAINT:
                    case ALTERTABLE_ADDCONSTRAINT:
                    case ALTERTABLE_FILEFORMAT:
                    case ALTERTABLE_LOCATION:
                    case ALTERTABLE_MERGEFILES:
                    case ALTERTABLE_PARTCOLTYPE:
                    case ALTERTABLE_PROPERTIES:
                    case ALTERTABLE_SETPARTSPEC:
                    case ALTERTABLE_EXECUTE:
                    case ALTERTABLE_CONVERT:
                    case ALTERDATACONNECTOR:
                    case ALTERDATACONNECTOR_OWNER:
                    case ALTERDATACONNECTOR_URL:
                    case ALTERTABLE_PROTECTMODE:
                    case ALTERTABLE_RENAME:
                    case ALTERTABLE_RENAMECOL:
                    case ALTERTABLE_RENAMEPART:
                    case ALTERTABLE_REPLACECOLS:
                    case ALTERTABLE_SERDEPROPERTIES:
                    case ALTERTABLE_SERIALIZER:
                    case ALTERTABLE_SKEWED:
                    case ALTERTABLE_TOUCH:
                    case ALTERTABLE_UNARCHIVE:
                    case ALTERTABLE_UPDATEPARTSTATS:
                    case ALTERTABLE_UPDATETABLESTATS:
                    case ALTERTABLE_UPDATECOLUMNS:
                    case ALTERTABLE_CREATEBRANCH:
                    case ALTERTABLE_DROPBRANCH:
                    case ALTERTABLE_CREATETAG:
                    case ALTERTABLE_DROPTAG:
                    case ALTERTBLPART_SKEWED_LOCATION:
                    case ALTERVIEW_PROPERTIES:
                    case ALTERVIEW_RENAME:
                    case ALTER_MATERIALIZED_VIEW_REWRITE:
                    case ALTER_MATERIALIZED_VIEW_REBUILD:
                        // HIVE-22188
                    case MSCK:
                        accessType = HiveAccessType.ALTER;
                        break;

                    case DROPFUNCTION:
                    case DROPTABLE:
                    case DROPVIEW:
                    case DROP_MATERIALIZED_VIEW:
                    case DROPDATABASE:
                    case DROPDATACONNECTOR:
                        accessType = HiveAccessType.DROP;
                        break;
                    // HIVE-21968
                    case IMPORT:
                    /*
                    This can happen during hive IMPORT command IFF a table is also being created as part of IMPORT.
                    If so then
                    - this would appear in the outputHObjs, i.e. accessType == false
                    - user then must have CREATE permission on the database

                    During IMPORT command it is not possible for a database to be in inputHObj list. Thus returning SELECT
                    when accessType==true is never expected to be hit in practice.
                     */
                        accessType = isInput ? HiveAccessType.SELECT : HiveAccessType.CREATE;
                        break;

                    case EXPORT:
                    case LOAD:
                        accessType = isInput ? HiveAccessType.SELECT : HiveAccessType.UPDATE;
                        break;

                    case LOCKDB:
                    case LOCKTABLE:
                    case UNLOCKDB:
                    case UNLOCKTABLE:
                        accessType = HiveAccessType.LOCK;
                        break;

                    /*
                     * SELECT access is done for many of these metadata operations since hive does not call back for filtering.
                     * Overtime these should move to _any/USE access (as hive adds support for filtering).
                     */
                    case QUERY:
                    case SHOW_TABLESTATUS:
                    case SHOW_CREATETABLE:
                    case SHOWPARTITIONS:
                    case SHOW_TBLPROPERTIES:
                    case ANALYZE_TABLE:
                        accessType = HiveAccessType.SELECT;
                        break;

                    case SHOWCOLUMNS:
                    case DESCTABLE:
                        switch (StringUtil.toLower(RangerHivePlugin.describeShowTableAuth)) {
                            case "show-allowed":
                                // This is not implemented so defaulting to current behaviour of blocking describe/show columns not to show any columns.
                                // This has to be implemented when hive provides the necessary filterListCmdObjects for
                                // SELECT/SHOWCOLUMS/DESCTABLE to filter the columns based on access provided in ranger.
                            case "none":
                            case "":
                                accessType = HiveAccessType.SELECT;
                                break;
                            case "show-all":
                                accessType = HiveAccessType.USE;
                                break;
                        }
                        break;

                    // any access done for metadata access of actions that have support from hive for filtering
                    case SHOWDATABASES:
                    case SHOWDATACONNECTORS:
                    case SHOW_GRANT:
                    case SWITCHDATABASE:
                    case DESCDATABASE:
                    case DESCDATACONNECTOR:
                    case SHOWTABLES:
                    case SHOWVIEWS:
                        accessType = HiveAccessType.USE;
                        break;

                    case TRUNCATETABLE:
                        accessType = HiveAccessType.UPDATE;
                        break;

                    case GRANT_PRIVILEGE:
                    case REVOKE_PRIVILEGE:
                        accessType = HiveAccessType.NONE; // access check will be performed at the ranger-admin side
                        break;

                    case REPLDUMP:
                    case REPLLOAD:
                    case REPLSTATUS:
                        accessType = HiveAccessType.REPLADMIN;
                        break;

                    case KILL_QUERY:
                    case CREATE_RESOURCEPLAN:
                    case SHOW_RESOURCEPLAN:
                    case ALTER_RESOURCEPLAN:
                    case DROP_RESOURCEPLAN:
                    case CREATE_TRIGGER:
                    case ALTER_TRIGGER:
                    case DROP_TRIGGER:
                    case CREATE_POOL:
                    case ALTER_POOL:
                    case DROP_POOL:
                    case CREATE_MAPPING:
                    case ALTER_MAPPING:
                    case DROP_MAPPING:
                    case LLAP_CACHE_PURGE:
                    case LLAP_CLUSTER_INFO:
                        accessType = HiveAccessType.SERVICEADMIN;
                        break;

                    case ADD:
                    case COMPILE:
                        accessType = HiveAccessType.TEMPUDFADMIN;
                        break;

                    case DELETE:
                    case CREATEMACRO:
                    case CREATEROLE:
                    case DESCFUNCTION:
                    case PREPARE:
                    case EXECUTE:
                    case DFS:
                    case DROPMACRO:
                    case DROPROLE:
                    case EXPLAIN:
                    case GRANT_ROLE:
                    case REVOKE_ROLE:
                    case RESET:
                    case SET:
                    case SHOWCONF:
                    case SHOWFUNCTIONS:
                    case SHOWLOCKS:
                    case SHOW_COMPACTIONS:
                    case SHOW_ROLES:
                    case SHOW_ROLE_GRANT:
                    case SHOW_ROLE_PRINCIPALS:
                    case SHOW_TRANSACTIONS:
                        break;
                }
                break;
        }

        return accessType;
    }

    private FsAction getURIAccessType(HiveOperationType hiveOpType) {
        FsAction ret = FsAction.NONE;

        switch (hiveOpType) {
            case LOAD:
            case IMPORT:
                ret = FsAction.READ;
                break;

            case EXPORT:
                ret = FsAction.WRITE;
                break;

            case CREATEDATABASE:
            case CREATEDATACONNECTOR:
            case CREATETABLE:
            case CREATETABLE_AS_SELECT:
            case CREATEFUNCTION:
            case DROPFUNCTION:
            case RELOADFUNCTION:
            case ALTERDATABASE:
            case ALTERDATABASE_LOCATION:
            case ALTERDATABASE_OWNER:
            case ALTERTABLE_ADDCOLS:
            case ALTERTABLE_REPLACECOLS:
            case ALTERTABLE_RENAMECOL:
            case ALTERTABLE_RENAMEPART:
            case ALTERTABLE_RENAME:
            case ALTERTABLE_DROPPARTS:
            case ALTERTABLE_ADDPARTS:
            case ALTERTABLE_TOUCH:
            case ALTERTABLE_ARCHIVE:
            case ALTERTABLE_UNARCHIVE:
            case ALTERTABLE_PROPERTIES:
            case ALTERTABLE_SETPARTSPEC:
            case ALTERTABLE_EXECUTE:
            case ALTERTABLE_CONVERT:
            case ALTERDATACONNECTOR:
            case ALTERDATACONNECTOR_OWNER:
            case ALTERDATACONNECTOR_URL:
            case ALTERTABLE_SERIALIZER:
            case ALTERTABLE_PARTCOLTYPE:
            case ALTERTABLE_DROPCONSTRAINT:
            case ALTERTABLE_ADDCONSTRAINT:
            case ALTERTABLE_SERDEPROPERTIES:
            case ALTERTABLE_CLUSTER_SORT:
            case ALTERTABLE_BUCKETNUM:
            case ALTERTABLE_UPDATETABLESTATS:
            case ALTERTABLE_UPDATEPARTSTATS:
            case ALTERTABLE_UPDATECOLUMNS:
            case ALTERTABLE_CREATEBRANCH:
            case ALTERTABLE_DROPBRANCH:
            case ALTERTABLE_CREATETAG:
            case ALTERTABLE_DROPTAG:
            case ALTERTABLE_PROTECTMODE:
            case ALTERTABLE_FILEFORMAT:
            case ALTERTABLE_LOCATION:
            case ALTERTABLE_MERGEFILES:
            case ALTERTABLE_SKEWED:
            case ALTERTABLE_COMPACT:
            case ALTERTABLE_EXCHANGEPARTITION:
            case ALTERPARTITION_SERIALIZER:
            case ALTERPARTITION_SERDEPROPERTIES:
            case ALTERPARTITION_BUCKETNUM:
            case ALTERPARTITION_PROTECTMODE:
            case ALTERPARTITION_FILEFORMAT:
            case ALTERPARTITION_LOCATION:
            case ALTERPARTITION_MERGEFILES:
            case ALTERTBLPART_SKEWED_LOCATION:
            case ALTERTABLE_OWNER:
            case ADD:
            case DELETE:
            case QUERY:
                ret = FsAction.ALL;
                break;

            case EXPLAIN:
            case DROPDATABASE:
            case DROPDATACONNECTOR:
            case SWITCHDATABASE:
            case LOCKDB:
            case UNLOCKDB:
            case DROPTABLE:
            case DESCTABLE:
            case DESCFUNCTION:
            case PREPARE:
            case EXECUTE:
            case MSCK:
            case ANALYZE_TABLE:
            case CACHE_METADATA:
            case SHOWDATABASES:
            case SHOWDATACONNECTORS:
            case SHOWTABLES:
            case SHOWCOLUMNS:
            case SHOW_TABLESTATUS:
            case SHOW_TBLPROPERTIES:
            case SHOW_CREATEDATABASE:
            case SHOW_CREATETABLE:
            case SHOWFUNCTIONS:
            case SHOWVIEWS:
            case SHOWPARTITIONS:
            case SHOWLOCKS:
            case SHOWCONF:
            case CREATEMACRO:
            case DROPMACRO:
            case CREATEVIEW:
            case DROPVIEW:
            case CREATE_MATERIALIZED_VIEW:
            case ALTERVIEW_PROPERTIES:
            case DROP_MATERIALIZED_VIEW:
            case ALTER_MATERIALIZED_VIEW_REWRITE:
            case ALTER_MATERIALIZED_VIEW_REBUILD:
            case LOCKTABLE:
            case UNLOCKTABLE:
            case CREATEROLE:
            case DROPROLE:
            case GRANT_PRIVILEGE:
            case REVOKE_PRIVILEGE:
            case SHOW_GRANT:
            case GRANT_ROLE:
            case REVOKE_ROLE:
            case SHOW_ROLES:
            case SHOW_ROLE_GRANT:
            case SHOW_ROLE_PRINCIPALS:
            case TRUNCATETABLE:
            case DESCDATABASE:
            case DESCDATACONNECTOR:
            case ALTERVIEW_RENAME:
            case ALTERVIEW_AS:
            case SHOW_COMPACTIONS:
            case SHOW_TRANSACTIONS:
            case ABORT_TRANSACTIONS:
            case ABORT_COMPACTION:
            case SET:
            case RESET:
            case DFS:
            case COMPILE:
            case START_TRANSACTION:
            case COMMIT:
            case ROLLBACK:
            case SET_AUTOCOMMIT:
            case GET_CATALOGS:
            case GET_COLUMNS:
            case GET_FUNCTIONS:
            case GET_SCHEMAS:
            case GET_TABLES:
            case GET_TABLETYPES:
            case GET_TYPEINFO:
            case REPLDUMP:
            case REPLLOAD:
            case REPLSTATUS:
            case KILL_QUERY:
            case LLAP_CACHE_PURGE:
            case LLAP_CLUSTER_INFO:
            case CREATE_RESOURCEPLAN:
            case SHOW_RESOURCEPLAN:
            case ALTER_RESOURCEPLAN:
            case DROP_RESOURCEPLAN:
            case CREATE_TRIGGER:
            case ALTER_TRIGGER:
            case DROP_TRIGGER:
            case CREATE_POOL:
            case ALTER_POOL:
            case DROP_POOL:
            case CREATE_MAPPING:
            case ALTER_MAPPING:
            case DROP_MAPPING:
                break;
        }

        return ret;
    }

    private String buildPathForException(String path, HiveOperationType hiveOpType) {
        String ret = path;

        switch (hiveOpType) {
            case DESCTABLE:
                ret = path + "/*";
                break;
            case QUERY:
                try {
                    int endIndex = StringUtils.ordinalIndexOf(path, "/", 2);

                    ret = path.substring(0, endIndex) + "/*";
                } catch (Exception e) {
                    //omit and return the path.Log error only in debug.
                    LOG.debug("RangerHiveAuthorizer.buildPathForException(): Error while creating exception message ", e);
                }
                break;
        }
        return ret;
    }

    private boolean isURIAccessAllowed(String userName, FsAction action, Path filePath, FileSystem fs) {
        return isURIAccessAllowed(userName, action, filePath, fs, RangerHivePlugin.uriPermissionCoarseCheck);
    }

    private boolean isURIAccessAllowed(String userName, FsAction action, Path filePath, FileSystem fs, boolean coarseCheck) {
        boolean ret;
        boolean recurse = !coarseCheck;

        if (action == FsAction.NONE) {
            ret = true;
        } else {
            try {
                FileStatus[] filestat = fs.globStatus(filePath);

                if (filestat != null && filestat.length > 0) {
                    boolean isDenied = false;

                    for (FileStatus file : filestat) {
                        if (FileUtils.isOwnerOfFileHierarchy(fs, file, userName) || FileUtils.isActionPermittedForFileHierarchy(fs, file, userName, action, recurse)) {
                            continue;
                        } else {
                            isDenied = true;
                            break;
                        }
                    }

                    ret = !isDenied;
                } else { // if given path does not exist then check for parent
                    FileStatus file = FileUtils.getPathOrParentThatExists(fs, filePath);

                    FileUtils.checkFileAccessWithImpersonation(fs, file, action, userName);

                    ret = true;
                }
            } catch (Exception excp) {
                ret = false;

                LOG.error("Error getting permissions for {}", filePath, excp);
            }
        }

        return ret;
    }

    private boolean isPathInFSScheme(String uri) {
        // This is to find if HIVE URI operation done is for hdfs,file scheme
        // else it may be for s3 which needs another set of authorization calls.
        boolean  ret      = false;
        String[] fsScheme = hivePlugin.getFSScheme();

        if (fsScheme != null) {
            for (String scheme : fsScheme) {
                if (!uri.isEmpty() && uri.startsWith(scheme)) {
                    ret = true;

                    break;
                }
            }
        }

        return ret;
    }

    /**
     * Resolves the path to actual target fs path. In the mount based file systems
     * like ViewHDFS, the resolved target path could be the path of other mounted
     * target fs path. Returns null if file does not exist or any other IOException.
     */
    private Path resolvePath(Path path, FileSystem fs) {
        try {
            return fs.resolvePath(path);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Returns true if the given fs supports mount functionality. In general we can
     * have child file systems only in the case of mount fs like ViewFileSystem,
     * ViewFsOverloadScheme or ViewDistributedFileSystem. Returns false if the
     * getChildFileSystems API returns null.
     */
    private boolean isMountedFs(FileSystem fs) {
        return fs.getChildFileSystems() != null;
    }

    private void handleDfsCommand(HiveOperationType hiveOpType, List<HivePrivilegeObject> inputHObjs, String user, RangerHiveAuditHandler auditHandler) throws HiveAccessControlException {
        String dfsCommandParams = null;

        if (inputHObjs != null) {
            for (HivePrivilegeObject hiveObj : inputHObjs) {
                if (hiveObj.getType() == HivePrivilegeObjectType.COMMAND_PARAMS) {
                    dfsCommandParams = StringUtil.toString(hiveObj.getCommandParams());

                    if (!StringUtil.isEmpty(dfsCommandParams)) {
                        break;
                    }
                }
            }
        }

        int    serviceType = -1;
        String serviceName = null;

        if (hivePlugin != null) {
            serviceType = hivePlugin.getServiceDefId();
            serviceName = hivePlugin.getServiceName();
        }

        auditHandler.logAuditEventForDfs(user, dfsCommandParams, false, serviceType, serviceName);

        throw new HiveAccessControlException(String.format("Permission denied: user [%s] does not have privilege for [%s] command", user, hiveOpType.name()));
    }

    private boolean existsByResourceAndAccessType(Collection<RangerHiveAccessRequest> requests, RangerHiveResource resource, HiveAccessType accessType) {
        boolean ret = false;

        if (requests != null && resource != null) {
            for (RangerHiveAccessRequest request : requests) {
                if (request.getHiveAccessType() == accessType && request.getResource().equals(resource)) {
                    ret = true;

                    break;
                }
            }
        }

        return ret;
    }

    private String getGrantorUsername(HivePrincipal grantorPrincipal) {
        String grantor = grantorPrincipal != null ? grantorPrincipal.getName() : null;

        if (StringUtil.isEmpty(grantor)) {
            UserGroupInformation ugi = this.getCurrentUserGroupInfo();

            grantor = ugi != null ? ugi.getShortUserName() : null;
        }

        return grantor;
    }

    private Set<String> getGrantorGroupNames(HivePrincipal grantorPrincipal) {
        Set<String>          ret     = null;
        String               grantor = grantorPrincipal != null ? grantorPrincipal.getName() : null;
        UserGroupInformation ugi     = StringUtil.isEmpty(grantor) ? this.getCurrentUserGroupInfo() : UserGroupInformation.createRemoteUser(grantor);
        String[]             groups  = ugi != null ? ugi.getGroupNames() : null;

        if (groups != null && groups.length > 0) {
            ret = new HashSet<>(Arrays.asList(groups));
        }

        return ret;
    }

    private GrantRevokeRequest createGrantRevokeData(RangerHiveResource resource, List<HivePrincipal> hivePrincipals, List<HivePrivilege> hivePrivileges, HivePrincipal grantorPrincipal, boolean grantOption) throws HiveAccessControlException {
        if (resource == null || !(resource.getObjectType() == HiveObjectType.DATABASE || resource.getObjectType() == HiveObjectType.TABLE || resource.getObjectType() == HiveObjectType.VIEW || resource.getObjectType() == HiveObjectType.COLUMN)) {
            throw new HiveAccessControlException("grant/revoke: unexpected object type '" + (resource == null ? null : resource.getObjectType().name()));
        }

        GrantRevokeRequest ret = new GrantRevokeRequest();

        ret.setGrantor(getGrantorUsername(grantorPrincipal));
        ret.setGrantorGroups(getGrantorGroupNames(grantorPrincipal));
        ret.setDelegateAdmin(grantOption ? Boolean.TRUE : Boolean.FALSE);
        ret.setEnableAudit(Boolean.TRUE);
        ret.setReplaceExistingPermissions(Boolean.FALSE);

        String              database    = StringUtils.isEmpty(resource.getDatabase()) ? "*" : resource.getDatabase();
        String              table       = StringUtils.isEmpty(resource.getTable()) ? "*" : resource.getTable();
        String              column      = StringUtils.isEmpty(resource.getColumn()) ? "*" : resource.getColumn();
        Map<String, String> mapResource = new HashMap<>();

        mapResource.put(RangerHiveResource.KEY_DATABASE, database);
        mapResource.put(RangerHiveResource.KEY_TABLE, table);
        mapResource.put(RangerHiveResource.KEY_COLUMN, column);

        ret.setOwnerUser(resource.getOwnerUser());
        ret.setResource(mapResource);

        SessionState ss = SessionState.get();

        if (ss != null) {
            ret.setClientIPAddress(ss.getUserIpAddress());
            ret.setSessionId(ss.getSessionId());

            HiveConf hiveConf = ss.getConf();

            if (hiveConf != null) {
                ret.setRequestData(hiveConf.get(HIVE_CONF_VAR_QUERY_STRING));
            }
        }

        HiveAuthzSessionContext sessionContext = getHiveAuthzSessionContext();

        if (sessionContext != null) {
            ret.setClientType(sessionContext.getClientType() == null ? null : sessionContext.getClientType().toString());
        }

        for (HivePrincipal principal : hivePrincipals) {
            switch (principal.getType()) {
                case USER:
                    ret.getUsers().add(principal.getName());
                    break;

                case GROUP:
                    ret.getGroups().add(principal.getName());
                    break;

                case ROLE:
                    ret.getRoles().add(principal.getName());
                    break;

                case UNKNOWN:
                    break;
            }
        }

        for (HivePrivilege privilege : hivePrivileges) {
            String privName = privilege.getName();

            if (StringUtils.equalsIgnoreCase(privName, HiveAccessType.ALL.name()) || StringUtils.equalsIgnoreCase(privName, HiveAccessType.ALTER.name()) || StringUtils.equalsIgnoreCase(privName, HiveAccessType.CREATE.name()) || StringUtils.equalsIgnoreCase(privName, HiveAccessType.DROP.name()) || StringUtils.equalsIgnoreCase(privName, HiveAccessType.INDEX.name()) || StringUtils.equalsIgnoreCase(privName, HiveAccessType.LOCK.name()) || StringUtils.equalsIgnoreCase(privName, HiveAccessType.SELECT.name()) || StringUtils.equalsIgnoreCase(privName, HiveAccessType.UPDATE.name())) {
                ret.getAccessTypes().add(privName.toLowerCase());
            } else if (StringUtils.equalsIgnoreCase(privName, "Insert") || StringUtils.equalsIgnoreCase(privName, "Delete")) {
                // Mapping Insert/Delete to Update
                ret.getAccessTypes().add(HiveAccessType.UPDATE.name().toLowerCase());
            } else {
                LOG.warn("grant/revoke: unexpected privilege type '{}'. Ignored", privName);
            }
        }

        return ret;
    }

    private HivePrivilegeObjectType getPluginPrivilegeObjType(org.apache.hadoop.hive.metastore.api.HiveObjectType objectType) {
        switch (objectType) {
            case DATABASE:
                return HivePrivilegeObjectType.DATABASE;
            case TABLE:
                return HivePrivilegeObjectType.TABLE_OR_VIEW;
            default:
                throw new AssertionError("Unexpected object type " + objectType);
        }
    }

    private RangerRequestedResources buildRequestContextWithAllAccessedResources(List<RangerHiveAccessRequest> requests) {
        RangerRequestedResources requestedResources = new RangerRequestedResources();

        for (RangerHiveAccessRequest request : requests) {
            // Build list of all things requested and put it in the context of each request
            RangerAccessRequestUtil.setRequestedResourcesInContext(request.getContext(), requestedResources);

            RangerHiveResource resource = (RangerHiveResource) request.getResource();

            if (resource.getObjectType() == HiveObjectType.COLUMN && StringUtils.contains(resource.getColumn(), COLUMN_SEP)) {
                String[] columns = StringUtils.split(resource.getColumn(), COLUMN_SEP);

                // in case of multiple columns, original request is not sent to the plugin; hence service-def will not be set
                resource.setServiceDef(hivePlugin.getServiceDef());

                for (String column : columns) {
                    if (column != null) {
                        column = column.trim();
                    }

                    if (StringUtils.isBlank(column)) {
                        continue;
                    }

                    RangerHiveResource colResource = new RangerHiveResource(HiveObjectType.COLUMN, resource.getDatabase(), resource.getTable(), column);

                    colResource.setOwnerUser(resource.getOwnerUser());
                    colResource.setServiceDef(hivePlugin.getServiceDef());

                    requestedResources.addRequestedResource(colResource);
                }
            } else {
                resource.setServiceDef(hivePlugin.getServiceDef());
                requestedResources.addRequestedResource(resource);
            }
        }

        LOG.debug("RangerHiveAuthorizer.buildRequestContextWithAllAccessedResources() - {}", requestedResources);

        return requestedResources;
    }

    private boolean isBlockAccessIfRowfilterColumnMaskSpecified(HiveOperationType hiveOpType, RangerHiveAccessRequest request) {
        boolean            ret      = false;
        RangerHiveResource resource = (RangerHiveResource) request.getResource();
        HiveObjectType     objType  = resource.getObjectType();

        if (objType == HiveObjectType.TABLE || objType == HiveObjectType.VIEW || objType == HiveObjectType.COLUMN) {
            ret = hiveOpType == HiveOperationType.EXPORT;

            if (!ret) {
                if (request.getHiveAccessType() == HiveAccessType.UPDATE && RangerHivePlugin.blockUpdateIfRowfilterColumnMaskSpecified) {
                    ret = true;
                }
            }
        }

        LOG.debug("isBlockAccessIfRowfilterColumnMaskSpecified({}, {}): {}", hiveOpType, request, ret);

        return ret;
    }

    private boolean isTempUDFOperation(String hiveOpTypeName, HivePrivilegeObject hiveObj) {
        // This happens for temp udf function and will use global resource policy in ranger for auth
        return (hiveOpTypeName.contains("createfunction") || hiveOpTypeName.contains("dropfunction")) && StringUtils.isEmpty(hiveObj.getDbname());
    }

    private List<HivePrivilegeInfo> getHivePrivilegeInfos(HivePrincipal principal, HivePrivilegeObject privObj) throws HiveAuthzPluginException {
        List<HivePrivilegeInfo>                     ret = new ArrayList<>();
        HivePrivilegeObject.HivePrivilegeObjectType objectType;
        Map<String, Map<Privilege, AccessResult>>   userPermissions;
        Map<String, Map<Privilege, AccessResult>>   groupPermissions;
        Map<String, Map<Privilege, AccessResult>>   rolePermissions;
        String                                      dbName;
        String                                      objectName;
        String                                      columnName;
        List<String>                                partValues;

        try {
            HiveObjectRef msObjRef = AuthorizationUtils.getThriftHiveObjectRef(privObj);

            if (msObjRef != null) {
                HivePrivilegeObject hivePrivilegeObject = null;

                if (msObjRef.getDbName() != null) {
                    // when resource is specified in the show grants, acl will be for that resource / user / groups
                    objectType          = getPluginPrivilegeObjType(msObjRef.getObjectType());
                    dbName              = msObjRef.getDbName();
                    objectName          = msObjRef.getObjectName();
                    columnName          = (msObjRef.getColumnName() == null) ? "" : msObjRef.getColumnName();
                    partValues          = (msObjRef.getPartValues() == null) ? new ArrayList<>() : msObjRef.getPartValues();
                    hivePrivilegeObject = new HivePrivilegeObject(objectType, dbName, objectName);

                    RangerResourceACLs rangerResourceACLs = getRangerResourceACLs(hivePrivilegeObject);

                    if (rangerResourceACLs != null) {
                        Map<String, Map<String, RangerResourceACLs.AccessResult>> userRangerACLs  = rangerResourceACLs.getUserACLs();
                        Map<String, Map<String, RangerResourceACLs.AccessResult>> groupRangerACLs = rangerResourceACLs.getGroupACLs();
                        Map<String, Map<String, RangerResourceACLs.AccessResult>> roleRangerACLs  = rangerResourceACLs.getRoleACLs();

                        userPermissions  = convertRangerACLsToHiveACLs(userRangerACLs);
                        groupPermissions = convertRangerACLsToHiveACLs(groupRangerACLs);
                        rolePermissions  = convertRangerACLsToHiveACLs(roleRangerACLs);

                        if (principal != null) {
                            if (principal.getType() == HivePrincipal.HivePrincipalType.USER) {
                                String                       user     = principal.getName();
                                Map<Privilege, AccessResult> userACLs = userPermissions.get(user);

                                if (userACLs != null) {
                                    Map<String, RangerResourceACLs.AccessResult> userAccessResult = userRangerACLs.get(user);

                                    for (Privilege userACL : userACLs.keySet()) {
                                        RangerPolicy policy = getRangerPolicy(userAccessResult, userACL.name());

                                        if (policy != null) {
                                            String            aclname       = getPermission(userACL, userAccessResult, policy);
                                            HivePrivilegeInfo privilegeInfo = createHivePrivilegeInfo(principal, objectType, dbName, objectName, columnName, partValues, aclname, policy);

                                            ret.add(privilegeInfo);
                                        }
                                    }
                                }

                                Set<String> groups = getPrincipalGroup(user);

                                for (String group : groups) {
                                    Map<Privilege, AccessResult> groupACLs = groupPermissions.get(group);

                                    if (groupACLs != null) {
                                        Map<String, RangerResourceACLs.AccessResult> groupAccessResult = groupRangerACLs.get(group);

                                        for (Privilege groupACL : groupACLs.keySet()) {
                                            RangerPolicy policy = getRangerPolicy(groupAccessResult, groupACL.name());

                                            if (policy != null) {
                                                String            aclname       = getPermission(groupACL, groupAccessResult, policy);
                                                HivePrivilegeInfo privilegeInfo = createHivePrivilegeInfo(principal, objectType, dbName, objectName, columnName, partValues, aclname, policy);

                                                ret.add(privilegeInfo);
                                            }
                                        }
                                    }
                                }
                            } else if (principal.getType() == HivePrincipal.HivePrincipalType.ROLE) {
                                String                       role     = principal.getName();
                                Map<Privilege, AccessResult> roleACLs = rolePermissions.get(role);

                                if (roleACLs != null) {
                                    Map<String, RangerResourceACLs.AccessResult> roleAccessResult = roleRangerACLs.get(role);

                                    for (Privilege roleACL : roleACLs.keySet()) {
                                        RangerPolicy policy = getRangerPolicy(roleAccessResult, roleACL.name());

                                        if (policy != null) {
                                            String            aclname       = getPermission(roleACL, roleAccessResult, policy);
                                            HivePrivilegeInfo privilegeInfo = createHivePrivilegeInfo(principal, objectType, dbName, objectName, columnName, partValues, aclname, policy);

                                            ret.add(privilegeInfo);
                                        }
                                    }
                                }
                            }
                        } else {
                            // Request is for all the ACLs on a resource
                            for (String user : userRangerACLs.keySet()) {
                                HivePrincipal                hivePrincipal = new HivePrincipal(user, HivePrincipal.HivePrincipalType.USER);
                                Map<Privilege, AccessResult> userACLs      = userPermissions.get(user);

                                if (userACLs != null) {
                                    Map<String, RangerResourceACLs.AccessResult> userAccessResult = userRangerACLs.get(user);

                                    for (Privilege userACL : userACLs.keySet()) {
                                        RangerPolicy policy = getRangerPolicy(userAccessResult, userACL.name());

                                        if (policy != null) {
                                            String            aclname       = getPermission(userACL, userAccessResult, policy);
                                            HivePrivilegeInfo privilegeInfo = createHivePrivilegeInfo(hivePrincipal, objectType, dbName, objectName, columnName, partValues, aclname, policy);

                                            ret.add(privilegeInfo);
                                        }
                                    }
                                }
                            }

                            for (String group : groupRangerACLs.keySet()) {
                                HivePrincipal                hivePrincipal = new HivePrincipal(group, HivePrincipal.HivePrincipalType.GROUP);
                                Map<Privilege, AccessResult> groupACLs     = groupPermissions.get(group);

                                if (groupACLs != null) {
                                    Map<String, RangerResourceACLs.AccessResult> groupAccessResult = groupRangerACLs.get(group);

                                    for (Privilege groupACL : groupACLs.keySet()) {
                                        RangerPolicy policy = getRangerPolicy(groupAccessResult, groupACL.name());

                                        if (policy != null) {
                                            String            aclname       = getPermission(groupACL, groupAccessResult, policy);
                                            HivePrivilegeInfo privilegeInfo = createHivePrivilegeInfo(hivePrincipal, objectType, dbName, objectName, columnName, partValues, aclname, policy);

                                            ret.add(privilegeInfo);
                                        }
                                    }
                                }
                            }

                            for (String role : roleRangerACLs.keySet()) {
                                HivePrincipal                hivePrincipal = new HivePrincipal(role, HivePrincipal.HivePrincipalType.ROLE);
                                Map<Privilege, AccessResult> roleACLs      = rolePermissions.get(role);

                                if (roleACLs != null) {
                                    Map<String, RangerResourceACLs.AccessResult> roleAccessResult = roleRangerACLs.get(role);

                                    for (Privilege roleACL : roleACLs.keySet()) {
                                        RangerPolicy policy = getRangerPolicy(roleAccessResult, roleACL.name());

                                        if (policy != null) {
                                            String            aclname       = getPermission(roleACL, roleAccessResult, policy);
                                            HivePrivilegeInfo privilegeInfo = createHivePrivilegeInfo(hivePrincipal, objectType, dbName, objectName, columnName, partValues, aclname, policy);

                                            ret.add(privilegeInfo);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new HiveAuthzPluginException("hive showPrivileges" + ": " + e.getMessage(), e);
        }

        return ret;
    }

    private RangerPolicy getRangerPolicy(Map<String, RangerResourceACLs.AccessResult> accessResults, String rangerACL) {
        RangerPolicy ret = null;

        if (MapUtils.isNotEmpty(accessResults)) {
            RangerResourceACLs.AccessResult accessResult = accessResults.get(rangerACL.toLowerCase());

            if (accessResult != null) {
                ret = accessResult.getPolicy();
            }
        }

        return ret;
    }

    private HivePrivilegeInfo createHivePrivilegeInfo(HivePrincipal hivePrincipal, HivePrivilegeObject.HivePrivilegeObjectType objectType, String dbName, String objectName, String columnName, List<String> partValues, String aclName, RangerPolicy policy) {
        HivePrivilegeInfo ret;
        int               creationDate  = 0;
        boolean           delegateAdmin = false;

        for (RangerPolicy.RangerPolicyItem policyItem : policy.getPolicyItems()) {
            List<RangerPolicyItemAccess> policyItemAccesses = policyItem.getAccesses();
            List<String>                 users              = policyItem.getUsers();
            List<String>                 groups             = policyItem.getGroups();
            List<String>                 accessTypes        = new ArrayList<>();

            for (RangerPolicyItemAccess policyItemAccess : policyItemAccesses) {
                accessTypes.add(policyItemAccess.getType());
            }

            if (accessTypes.contains(aclName.toLowerCase()) && (users.contains(hivePrincipal.getName()) || groups.contains(hivePrincipal.getName()))) {
                creationDate  = (policy.getCreateTime() == null) ? creationDate : (int) (policy.getCreateTime().getTime() / 1000);
                delegateAdmin = (policyItem.getDelegateAdmin() == null) ? delegateAdmin : policyItem.getDelegateAdmin();
            }
        }

        HivePrincipal       grantorPrincipal = new HivePrincipal(DEFAULT_RANGER_POLICY_GRANTOR, HivePrincipal.HivePrincipalType.USER);
        HivePrivilegeObject privilegeObject  = new HivePrivilegeObject(objectType, dbName, objectName, partValues, columnName);
        HivePrivilege       privilege        = new HivePrivilege(aclName, null);

        ret = new HivePrivilegeInfo(hivePrincipal, privilege, privilegeObject, grantorPrincipal, delegateAdmin, creationDate);

        return ret;
    }

    private Set<String> getPrincipalGroup(String user) {
        UserGroupInformation ugi = UserGroupInformation.createRemoteUser(user);

        return Sets.newHashSet(ugi.getGroupNames());
    }

    private RangerResourceACLs getRangerResourceACLs(HivePrivilegeObject hiveObject) {
        LOG.debug("==> RangerHivePolicyProvider.getRangerResourceACLs:[{}]", hiveObject);

        RangerResourceACLs      ret;
        RangerHiveResource      hiveResource = createHiveResource(hiveObject);
        RangerAccessRequestImpl request      = new RangerAccessRequestImpl(hiveResource, RangerPolicyEngine.ANY_ACCESS, null, null, null);

        ret = hivePlugin.getResourceACLs(request);

        LOG.debug("<== RangerHivePolicyProvider.getRangerResourceACLs:[{}], Computed ACLS:[{}]", hiveObject, ret);

        return ret;
    }

    private Map<String, Map<Privilege, AccessResult>> convertRangerACLsToHiveACLs(Map<String, Map<String, RangerResourceACLs.AccessResult>> rangerACLs) {
        Map<String, Map<Privilege, AccessResult>> ret = new HashMap<>();

        if (MapUtils.isNotEmpty(rangerACLs)) {
            Set<String> hivePrivileges = new HashSet<>();

            for (Privilege privilege : Privilege.values()) {
                hivePrivileges.add(privilege.name().toLowerCase());
            }

            for (Map.Entry<String, Map<String, RangerResourceACLs.AccessResult>> entry : rangerACLs.entrySet()) {
                Map<Privilege, AccessResult> permissions = new HashMap<>();

                ret.put(entry.getKey(), permissions);

                for (Map.Entry<String, RangerResourceACLs.AccessResult> permission : entry.getValue().entrySet()) {
                    if (hivePrivileges.contains(permission.getKey())) {
                        Privilege    privilege         = Privilege.valueOf(StringUtils.upperCase(permission.getKey()));
                        int          rangerResultValue = permission.getValue().getResult();
                        AccessResult accessResult;

                        if (rangerResultValue == RangerPolicyEvaluator.ACCESS_ALLOWED) {
                            accessResult = AccessResult.ALLOWED;
                        } else if (rangerResultValue == RangerPolicyEvaluator.ACCESS_DENIED) {
                            accessResult = AccessResult.NOT_ALLOWED;
                        } else if (rangerResultValue == RangerPolicyEvaluator.ACCESS_CONDITIONAL) {
                            accessResult = AccessResult.CONDITIONAL_ALLOWED;
                        } else {
                            // Should not get here
                            accessResult = AccessResult.NOT_ALLOWED;
                        }

                        permissions.put(privilege, accessResult);
                    }
                }
            }
        }

        return ret;
    }

    private String getPermission(Privilege acl, Map<String, RangerResourceACLs.AccessResult> accessResultMap, RangerPolicy policy) {
        String aclname   = acl.name();
        int    aclResult = checkACLIsAllowed(acl, accessResultMap);

        if (aclResult > RangerPolicyEvaluator.ACCESS_DENIED) {
            // Other than denied ACLs are considered
            if (policy != null) {
                if (aclResult == RangerPolicyEvaluator.ACCESS_UNDETERMINED) {
                    aclname = aclname + " " + "(ACCESS_UNDETERMINED)";
                } else if (aclResult == RangerPolicyEvaluator.ACCESS_CONDITIONAL) {
                    aclname = aclname + " " + "(ACCESS_CONDITIONAL)";
                }
            }
        }

        return aclname;
    }

    private int checkACLIsAllowed(Privilege acl, Map<String, RangerResourceACLs.AccessResult> accessResultMap) {
        int                             result       = -1;
        String                          aclName      = acl.name().toLowerCase();
        RangerResourceACLs.AccessResult accessResult = accessResultMap.get(aclName);

        if (accessResult != null) {
            result = accessResult.getResult();
        }

        return result;
    }

    private String toString(HiveOperationType hiveOpType, List<HivePrivilegeObject> inputHObjs, List<HivePrivilegeObject> outputHObjs, HiveAuthzContext context, HiveAuthzSessionContext sessionContext) {
        StringBuilder sb = new StringBuilder();

        sb.append("'checkPrivileges':{");
        sb.append("'hiveOpType':").append(hiveOpType);

        sb.append(", 'inputHObjs':[");
        toString(inputHObjs, sb);
        sb.append("]");

        sb.append(", 'outputHObjs':[");
        toString(outputHObjs, sb);
        sb.append("]");

        sb.append(", 'context':{");
        sb.append("'clientType':").append(sessionContext == null ? null : sessionContext.getClientType());
        sb.append(", 'commandString':").append(context == null ? "null" : context.getCommandString());
        sb.append(", 'ipAddress':").append(context == null ? "null" : context.getIpAddress());
        sb.append(", 'forwardedAddresses':").append(context == null ? "null" : StringUtils.join(context.getForwardedAddresses(), ", "));
        sb.append(", 'sessionString':").append(sessionContext == null ? "null" : sessionContext.getSessionString());
        sb.append("}");

        sb.append(", 'user':").append(this.getCurrentUserGroupInfo().getUserName());
        sb.append(", 'groups':[").append(StringUtil.toString(this.getCurrentUserGroupInfo().getGroupNames())).append("]");
        sb.append("}");

        return sb.toString();
    }

    private StringBuilder toString(List<HivePrivilegeObject> privObjs, StringBuilder sb) {
        if (privObjs != null && !privObjs.isEmpty()) {
            toString(privObjs.get(0), sb);

            for (int i = 1; i < privObjs.size(); i++) {
                sb.append(",");

                toString(privObjs.get(i), sb);
            }
        }

        return sb;
    }

    private StringBuilder toString(HivePrivilegeObject privObj, StringBuilder sb) {
        sb.append("'HivePrivilegeObject':{");
        sb.append("'type':").append(privObj.getType().toString());
        sb.append(", 'dbName':").append(privObj.getDbname());
        sb.append(", 'objectType':").append(privObj.getType());
        sb.append(", 'objectName':").append(privObj.getObjectName());
        sb.append(", 'columns':[").append(StringUtil.toString(privObj.getColumns())).append("]");
        sb.append(", 'partKeys':[").append(StringUtil.toString(privObj.getPartKeys())).append("]");
        sb.append(", 'commandParams':[").append(StringUtil.toString(privObj.getCommandParams())).append("]");
        sb.append(", 'actionType':").append(privObj.getActionType().toString());
        //sb.append(", 'owner':").append(privObj.getOwnerName());
        sb.append("}");

        return sb;
    }

    private RangerAccessResult createAuditEvent(RangerHivePlugin hivePlugin, String userOrGrantor, List<String> roleUsers, HiveOperationType hiveOperationType, HiveAccessType accessType, List<String> roleNames, boolean result) {
        RangerHiveAccessRequest rangerHiveAccessRequest = createRangerHiveAccessRequest(userOrGrantor, roleUsers, hiveOperationType, accessType, roleNames);

        return createRangerHiveAccessResult(hivePlugin, userOrGrantor, rangerHiveAccessRequest, result);
    }

    private RangerHiveAccessRequest createRangerHiveAccessRequest(String userOrGrantor, List<String> roleUsers, HiveOperationType hiveOperationType, HiveAccessType accessType, List<String> roleNames) {
        HiveAuthzContext.Builder builder       = new HiveAuthzContext.Builder();
        String                   roleNameStr   = createRoleString(roleNames);
        String                   userNameStr   = createUserString(roleUsers);
        String                   commandString = getCommandString(hiveOperationType, userNameStr, roleNameStr);
        String                   cmdStr        = (commandString != null) ? commandString : StringUtils.EMPTY;

        builder.setCommandString(cmdStr);

        HiveAuthzContext hiveAuthzContext = builder.build();

        RangerHiveResource      rangerHiveResource = new RangerHiveResource(HiveObjectType.GLOBAL, "*");
        RangerHiveAccessRequest ret                = new RangerHiveAccessRequest(rangerHiveResource, userOrGrantor, null, null, hiveOperationType, accessType, hiveAuthzContext, null);

        ret.setClusterName(hivePlugin.getClusterName());
        ret.setAction(hiveOperationType.name());
        ret.setClientIPAddress(getRemoteIp());
        ret.setRemoteIPAddress(getRemoteIp());

        return ret;
    }

    private RangerAccessResult createRangerHiveAccessResult(RangerHivePlugin hivePlugin, String userOrGrantor, RangerHiveAccessRequest rangerHiveAccessRequest, boolean result) {
        String           serviceName = hivePlugin.getServiceName();
        RangerServiceDef serviceDef  = hivePlugin.getServiceDef();
        String           reason      = String.format("%s is not an Admin", userOrGrantor);

        if (result) {
            reason = String.format("%s is Admin", userOrGrantor);
        }

        RangerAccessResult ret = new RangerAccessResult(RangerPolicy.POLICY_TYPE_ACCESS, serviceName, serviceDef, rangerHiveAccessRequest);

        ret.setIsAccessDetermined(true);
        ret.setIsAudited(true);
        ret.setIsAllowed(result);
        ret.setAuditPolicyId(-1);
        ret.setPolicyId(-1);
        ret.setPolicyPriority(RangerPolicy.POLICY_PRIORITY_NORMAL);
        ret.setZoneName(null);
        ret.setPolicyVersion(null);
        ret.setReason(reason);
        ret.setAdditionalInfo(MapUtils.EMPTY_MAP);

        return ret;
    }

    private String getCommandString(HiveOperationType hiveOperationType, String user, String roleName) {
        String ret = StringUtils.EMPTY;

        switch (hiveOperationType) {
            case CREATEROLE:
                ret = String.format(CMD_CREATE_ROLE, roleName);
                break;
            case DROPROLE:
                ret = String.format(CMD_DROP_ROLE, roleName);
                break;
            case SHOW_ROLES:
                ret = CMD_SHOW_ROLES;
                break;
            case SHOW_ROLE_GRANT:
                ret = String.format(CMD_SHOW_ROLE_GRANT, roleName);
                break;
            case SHOW_ROLE_PRINCIPALS:
                ret = String.format(CMD_SHOW_PRINCIPALS, roleName);
                break;
            case GRANT_ROLE:
                ret = String.format(CMD_GRANT_ROLE, roleName, user);
                break;
            case REVOKE_ROLE:
                ret = String.format(CMD_REVOKE_ROLE, roleName, user);
                break;
            case SET:
                ret = String.format(CMD_SET_ROLE, roleName);
        }

        return ret;
    }

    private String createRoleString(List<String> roleNames) {
        String ret;

        if (CollectionUtils.isEmpty(roleNames)) {
            ret = StringUtils.EMPTY;
        } else {
            if (roleNames.size() > 1) {
                ret = StringUtils.join(roleNames, ",");
            } else {
                ret = roleNames.get(0);
            }
        }

        return ret;
    }

    private String createUserString(List<String> userNames) {
        String ret;

        if (CollectionUtils.isEmpty(userNames)) {
            ret = StringUtils.EMPTY;
        } else {
            if (userNames.size() > 1) {
                ret = StringUtils.join(userNames, ",");
            } else {
                ret = userNames.get(0);
            }
        }

        return ret;
    }

    private static String getRemoteIp() {
        SessionState ss  = SessionState.get();
        String       ret = (ss != null) ? ss.getUserIpAddress() : null;

        LOG.debug("RangerHiveAuthorizer.getRemoteIp()={}", ret);

        return ret;
    }

    private Set<String> getCurrentRoles() {
        // from SQLStdHiveAccessController.getCurrentRoles()
        getCurrentRoleForCurrentUser();

        return currentRoles;
    }

    private void initUserRoles() {
        LOG.debug(" ==> RangerHiveAuthorizer.initUserRoles()");

        // from SQLStdHiveAccessController.initUserRoles()
        // to aid in testing through .q files, authenticator is passed as argument to
        // the interface. this helps in being able to switch the user within a session.
        // so we need to check if the user has changed
        String newUserName = getHiveAuthenticator().getUserName();

        if (Objects.equals(currentUserName, newUserName)) {
            // no need to (re-)initialize the currentUserName, currentRoles fields
            return;
        }

        this.currentUserName = newUserName;

        try {
            currentRoles = getCurrentRoleNamesFromRanger();
        } catch (HiveAuthzPluginException e) {
            LOG.error("Error while fetching roles from ranger for user : {}", currentUserName, e);
        }

        LOG.info("Current user : {}, Current Roles : {}", currentUserName, currentRoles);
    }

    private void getCurrentRoleForCurrentUser() {
        if (isCurrentRoleSet) {
            // current session has a role set, so no need to fetch roles.
            return;
        }

        this.currentUserName = getHiveAuthenticator().getUserName();

        try {
            currentRoles = getCurrentRoleNamesFromRanger();
        } catch (HiveAuthzPluginException e) {
            LOG.error("Error while fetching roles from ranger for user : {}", currentUserName, e);
        }

        LOG.info("Current user : {}, Current Roles : {}", currentUserName, currentRoles);
    }

    private Set<String> getCurrentRolesForUser(String user, Set<String> groups) {
        LOG.debug("==> RangerHiveAuthorizer.getCurrentRolesForUser()");

        Set<String> ret = hivePlugin.getRolesFromUserAndGroups(user, groups);

        ret = (isCurrentRoleSet) ? currentRoles : ret;

        LOG.debug("<== RangerHiveAuthorizer.getCurrentRolesForUser() User: {}, User Roles: {}", currentUserName, ret);

        return ret;
    }

    private Set<String> getCurrentRoleNamesFromRanger() throws HiveAuthzPluginException {
        LOG.debug("==> RangerHiveAuthorizer.getCurrentRoleNamesFromRanger()");

        boolean              result = false;
        UserGroupInformation ugi    = getCurrentUserGroupInfo();

        if (ugi == null) {
            throw new HiveAuthzPluginException("User information not available");
        }

        Set<String> ret    = new HashSet<>();
        String      user   = ugi.getShortUserName();
        Set<String> groups = Sets.newHashSet(ugi.getGroupNames());

        RangerHiveAuditHandler auditHandler = new RangerHiveAuditHandler(hivePlugin.getConfig());

        try {
            LOG.debug("==> RangerHiveAuthorizer.getCurrentRoleNamesFromRanger() for user {}, userGroups: {}", user, groups);

            Set<String> userRoles = new HashSet<>(getRolesforUserAndGroups(user, groups));

            for (String role : userRoles) {
                if (!ROLE_ADMIN.equalsIgnoreCase(role)) {
                    ret.add(role);
                } else {
                    this.adminRole = role;
                }
            }
            result = true;
        } catch (Exception excp) {
            throw new HiveAuthzPluginException(excp);
        } finally {
            List<String>       roleNames    = new ArrayList<>(ret);
            RangerAccessResult accessResult = createAuditEvent(hivePlugin, currentUserName, roleNames, HiveOperationType.SHOW_ROLES, HiveAccessType.SELECT, null, result);

            auditHandler.processResult(accessResult);
            auditHandler.flushAudit();
        }

        LOG.debug("<== RangerHiveAuthorizer.getCurrentRoleNamesFromRanger() for user: {}, userGroups: {}, roleNames: {}", user, groups, ret);

        return ret;
    }

    private Set<String> getRolesforUserAndGroups(String user, Set<String> groups) {
        LOG.debug("==> RangerHiveAuthorizer.getRolesforUserAndGroups()");

        Set<String> ret = null;

        if (hivePlugin != null) {
            ret = hivePlugin.getRolesFromUserAndGroups(user, groups);
        }

        LOG.debug("<== RangerHiveAuthorizer.getRolesforUserAndGroups(), user: {}, groups: {}, roles: {}", user, groups, ret);

        return ret != null ? ret : Collections.emptySet();
    }

    private HiveRoleGrant getHiveRoleGrant(RangerRole role, RoleMember roleMember, String type) {
        HiveRoleGrant ret = new HiveRoleGrant();

        ret.setRoleName(role.getName());
        ret.setGrantOption(roleMember.getIsAdmin());
        ret.setGrantor(role.getCreatedByUser());
        ret.setGrantorType(HivePrincipal.HivePrincipalType.USER.name());
        ret.setPrincipalName(roleMember.getName());
        ret.setPrincipalType(type);

        if (role.getUpdateTime() != null) {
            ret.setGrantTime((int) (role.getUpdateTime().getTime() / 1000));
        }

        return ret;
    }

    private RangerRole getRangerRoleForRoleName(String roleName) {
        RangerRole  ret         = null;
        RangerRoles rangerRoles = hivePlugin.getRangerRoles();

        if (rangerRoles != null) {
            Set<RangerRole> roles = rangerRoles.getRangerRoles();

            for (RangerRole role : roles) {
                if (roleName.equals(role.getName())) {
                    ret = role;

                    break;
                }
            }
        }

        return ret;
    }

    private RangerHiveAccessRequest buildRequestForAlterTableSetOwnerFromCommandString(String user, Set<String> userGroups, Set<String> userRoles, String hiveOpTypeName, HiveAuthzContext context, HiveAuthzSessionContext sessionContext) {
        RangerHiveResource      resource;
        RangerHiveAccessRequest request = null;
        HiveObj                 hiveObj = new HiveObj();

        hiveObj.fetchHiveObjForAlterTable(context);

        String dbName    = hiveObj.getDatabaseName();
        String tableName = hiveObj.getTableName();

        LOG.debug("Database: {} Table: {}", dbName, tableName);

        if (dbName != null && tableName != null) {
            resource = new RangerHiveResource(HiveObjectType.TABLE, dbName, tableName);
            request  = new RangerHiveAccessRequest(resource, user, userGroups, userRoles, hiveOpTypeName, HiveAccessType.ALTER, context, sessionContext);
        }

        return request;
    }

    private static String getColumnType(HivePrivilegeObject hiveObj, String colName, IMetaStoreClient metaStoreClient) {
        String ret = null;

        if (hiveObj != null && metaStoreClient != null) {
            try {
                switch (hiveObj.getType()) {
                    case TABLE_OR_VIEW:
                    case COLUMN:
                        Table table = metaStoreClient.getTable(hiveObj.getDbname(), hiveObj.getObjectName());
                        List<FieldSchema> cols = table != null && table.getSd() != null ? table.getSd().getCols() : null;

                        if (CollectionUtils.isNotEmpty(cols)) {
                            for (FieldSchema col : cols) {
                                if (StringUtils.equalsIgnoreCase(col.getName(), colName)) {
                                    ret = col.getType();
                                    break;
                                }
                            }
                        }
                        break;
                }
            } catch (Exception excp) {
                LOG.error("failed to get column type from Hive metastore. dbName={}, tblName={}, colName={}", hiveObj.getDbname(), hiveObj.getObjectName(), colName, excp);
            }
        }

        LOG.debug("getColumnType({}, {}): columnType={}", hiveObj, colName, ret);

        return ret;
    }

    private IMetaStoreClient getMetaStoreClient() {
        IMetaStoreClient ret = null;

        try {
            ret = getMetastoreClientFactory().getHiveMetastoreClient();
        } catch (HiveAuthzPluginException excp) {
            LOG.warn("failed to get meta-store client", excp);
        }

        return ret;
    }

    public enum HiveObjectType { NONE, DATABASE, TABLE, VIEW, PARTITION, INDEX, COLUMN, FUNCTION, URI, SERVICE_NAME, GLOBAL }

    public enum HiveAccessType { NONE, CREATE, ALTER, DROP, INDEX, LOCK, SELECT, UPDATE, USE, READ, WRITE, ALL, REPLADMIN, SERVICEADMIN, TEMPUDFADMIN }

    static {
        Set<String> roleNames = new HashSet<>();

        roleNames.add(ROLE_ALL);
        roleNames.add(ROLE_DEFAULT);
        roleNames.add(ROLE_NONE);

        RESERVED_ROLE_NAMES = Collections.unmodifiableSet(roleNames);
    }

    private static class HiveObj {
        String databaseName;
        String tableName;

        HiveObj() {}

        HiveObj(HiveAuthzContext context) {
            fetchHiveObj(context);
        }

        public String getDatabaseName() {
            return databaseName;
        }

        public String getTableName() {
            return tableName;
        }

        public void fetchHiveObjForAlterTable(HiveAuthzContext context) {
            // cmd passed: Alter Table <database.tableName or tableName> set owner user|role  <user_or_role>
            if (context != null) {
                String cmdString = context.getCommandString();

                if (cmdString != null) {
                    String[] cmd = cmdString.trim().split("\\s+");

                    if (!ArrayUtils.isEmpty(cmd) && cmd.length > 2) {
                        tableName = cmd[2];

                        if (tableName.contains(".")) {
                            String[] result = splitDBName(tableName);

                            databaseName = result[0];
                            tableName    = result[1];
                        } else {
                            SessionState sessionState = SessionState.get();

                            if (sessionState != null) {
                                databaseName = sessionState.getCurrentDatabase();
                            }
                        }
                    }
                }
            }
        }

        private void fetchHiveObj(HiveAuthzContext context) {
            if (context != null) {
                String cmdString = context.getCommandString();

                if (cmdString != null) {
                    String[] cmd = cmdString.trim().split("\\s+");

                    if (!ArrayUtils.isEmpty(cmd) && cmd.length > 2) {
                        String dbName = cmd[2];

                        if (dbName.contains(".")) {
                            String[] result = splitDBName(dbName);

                            databaseName = result[0];
                            tableName    = result[1];
                        } else {
                            databaseName = dbName;
                            tableName    = null;
                        }
                    }
                }
            }
        }

        private String[] splitDBName(String dbName) {
            return dbName.split("\\.");
        }
    }

    private static class RangerHivePlugin extends RangerBasePlugin {
        private static final String RANGER_PLUGIN_HIVE_ULRAUTH_FILESYSTEM_SCHEMES         = "ranger.plugin.hive.urlauth.filesystem.schemes";
        private static final String RANGER_PLUGIN_HIVE_ULRAUTH_FILESYSTEM_SCHEMES_DEFAULT = "hdfs:,file:";
        private static final String FILESYSTEM_SCHEMES_SEPARATOR_CHAR                     = ",";

        public static boolean uriPermissionCoarseCheck                  = RangerHadoopConstants.HIVE_URI_PERMISSION_COARSE_CHECK_DEFAULT_VALUE;
        public static boolean updateXaPoliciesOnGrantRevoke             = RangerHadoopConstants.HIVE_UPDATE_RANGER_POLICIES_ON_GRANT_REVOKE_DEFAULT_VALUE;
        public static boolean blockUpdateIfRowfilterColumnMaskSpecified = RangerHadoopConstants.HIVE_BLOCK_UPDATE_IF_ROWFILTER_COLUMNMASK_SPECIFIED_DEFAULT_VALUE;
        public static String  describeShowTableAuth                     = RangerHadoopConstants.HIVE_DESCRIBE_TABLE_SHOW_COLUMNS_AUTH_OPTION_PROP_DEFAULT_VALUE;

        private String[] fsScheme;

        public RangerHivePlugin(String appType) {
            super("hive", appType);
        }

        @Override
        public void init() {
            super.init();

            RangerHivePlugin.uriPermissionCoarseCheck                  = getConfig().getBoolean(RangerHadoopConstants.HIVE_URI_PERMISSION_COARSE_CHECK, RangerHadoopConstants.HIVE_URI_PERMISSION_COARSE_CHECK_DEFAULT_VALUE);
            RangerHivePlugin.updateXaPoliciesOnGrantRevoke             = getConfig().getBoolean(RangerHadoopConstants.HIVE_UPDATE_RANGER_POLICIES_ON_GRANT_REVOKE_PROP, RangerHadoopConstants.HIVE_UPDATE_RANGER_POLICIES_ON_GRANT_REVOKE_DEFAULT_VALUE);
            RangerHivePlugin.blockUpdateIfRowfilterColumnMaskSpecified = getConfig().getBoolean(RangerHadoopConstants.HIVE_BLOCK_UPDATE_IF_ROWFILTER_COLUMNMASK_SPECIFIED_PROP, RangerHadoopConstants.HIVE_BLOCK_UPDATE_IF_ROWFILTER_COLUMNMASK_SPECIFIED_DEFAULT_VALUE);
            RangerHivePlugin.describeShowTableAuth                     = getConfig().get(RangerHadoopConstants.HIVE_DESCRIBE_TABLE_SHOW_COLUMNS_AUTH_OPTION_PROP, RangerHadoopConstants.HIVE_DESCRIBE_TABLE_SHOW_COLUMNS_AUTH_OPTION_PROP_DEFAULT_VALUE);

            String fsSchemesString = getConfig().get(RANGER_PLUGIN_HIVE_ULRAUTH_FILESYSTEM_SCHEMES, RANGER_PLUGIN_HIVE_ULRAUTH_FILESYSTEM_SCHEMES_DEFAULT);

            fsScheme = StringUtils.split(fsSchemesString, FILESYSTEM_SCHEMES_SEPARATOR_CHAR);

            if (fsScheme != null) {
                for (int i = 0; i < fsScheme.length; i++) {
                    fsScheme[i] = fsScheme[i].trim();
                }
            }
        }

        public String[] getFSScheme() {
            return fsScheme;
        }
    }
}
