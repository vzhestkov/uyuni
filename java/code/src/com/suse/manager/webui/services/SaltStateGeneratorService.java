/**
 * Copyright (c) 2016 SUSE LLC
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package com.suse.manager.webui.services;

import static com.suse.manager.webui.services.SaltConstants.PILLAR_DATA_FILE_EXT;
import static com.suse.manager.webui.services.SaltConstants.PILLAR_DATA_FILE_PREFIX;
import static com.suse.manager.webui.services.SaltConstants.SALT_CONFIG_STATES_DIR;
import static com.suse.manager.webui.services.SaltConstants.SALT_SERVER_STATE_FILE_PREFIX;
import static com.suse.manager.webui.services.SaltConstants.SUMA_PILLAR_DATA_PATH;
import static com.suse.manager.webui.services.SaltConstants.SUMA_STATE_FILES_ROOT_PATH;
import static com.suse.manager.webui.utils.SaltFileUtils.defaultExtension;

import com.redhat.rhn.common.conf.Config;
import com.redhat.rhn.common.conf.ConfigDefaults;
import com.redhat.rhn.domain.channel.AccessToken;
import com.redhat.rhn.domain.channel.AccessTokenFactory;
import com.redhat.rhn.domain.channel.Channel;
import com.redhat.rhn.domain.config.ConfigChannel;
import com.redhat.rhn.domain.org.Org;
import com.redhat.rhn.domain.server.ManagedServerGroup;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.MinionServerFactory;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.domain.server.ServerGroup;
import com.redhat.rhn.domain.server.ServerGroupFactory;
import com.redhat.rhn.domain.server.ServerPath;
import com.redhat.rhn.domain.state.OrgStateRevision;
import com.redhat.rhn.domain.state.ServerGroupStateRevision;
import com.redhat.rhn.domain.state.ServerStateRevision;
import com.redhat.rhn.domain.state.StateFactory;
import com.redhat.rhn.domain.state.StateRevision;
import com.redhat.rhn.domain.user.User;

import com.suse.manager.utils.MachinePasswordUtils;
import com.suse.manager.webui.controllers.StatesAPI;
import com.suse.manager.webui.utils.SaltConfigChannelState;
import com.suse.manager.webui.utils.SaltPillar;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service to manage the Salt states generated by Suse Manager.
 */
public enum SaltStateGeneratorService {

    // Singleton instance of this class
    INSTANCE;

    private static final Map<String, Object> PKGSET_BEACON_PROPS = new HashMap<>();
    private static final Map<String, Object> VIRTPOLLER_BEACON_PROPS = new HashMap<>();

    private static final String PKGSET_COOKIE_PATH = "/var/cache/salt/minion/rpmdb.cookie";
    private static final int PKGSET_INTERVAL = 5;
    static {
        PKGSET_BEACON_PROPS.put("cookie", PKGSET_COOKIE_PATH);
        PKGSET_BEACON_PROPS.put("interval",  PKGSET_INTERVAL);
    }

    static {
        VIRTPOLLER_BEACON_PROPS.put("cache_file", Config.get().getString(
                ConfigDefaults.VIRTPOLLER_CACHE_FILE));
        VIRTPOLLER_BEACON_PROPS.put("expire_time", Config.get().getInt(
                ConfigDefaults.VIRTPOLLER_CACHE_EXPIRATION));
        VIRTPOLLER_BEACON_PROPS.put("interval", Config.get().getInt(
                ConfigDefaults.VIRTPOLLER_INTERVAL));
    }

    /** Logger */
    private static final Logger LOG = Logger.getLogger(SaltStateGeneratorService.class);

    private Path suseManagerStatesFilesRoot;

    private Path pillarDataPath;

    SaltStateGeneratorService() {
        suseManagerStatesFilesRoot = Paths.get(SUMA_STATE_FILES_ROOT_PATH);
        pillarDataPath = Paths.get(SUMA_PILLAR_DATA_PATH);
    }

    /**
     * Generate server specific pillar if the given server is a minion.
     * @param minion the minion server
     */
    public void generatePillar(MinionServer minion) {
        generatePillar(minion, true, Collections.emptySet());
    }

    /**
     * Generate server specific pillar if the given server is a minion.
     * @param minion the minion server
     * @param refreshAccessTokens if access tokens should be refreshed first
     * @param tokensToActivate channels access tokens to activate when refreshing
     * the tokens
     */
    public void generatePillar(MinionServer minion, boolean refreshAccessTokens,
                               Collection<AccessToken> tokensToActivate) {
        LOG.debug("Generating pillar file for minion: " + minion.getMinionId());

        if (refreshAccessTokens) {
            AccessTokenFactory.refreshTokens(minion, tokensToActivate);
        }

        List<ManagedServerGroup> groups = ServerGroupFactory.listManagedGroups(minion);
        List<String> addonGroupTypes =
                ServerGroupFactory.listEntitlementGroups(minion).stream().flatMap(group -> {
                    if (group.getGroupType() != null) {
                        return Stream.of(group.getGroupType().getLabel());
                    }
                    else {
                        return Stream.empty();
                    }
                }).collect(Collectors.toList());

        List<Long> groupIds = groups.stream()
                .map(ServerGroup::getId).collect(Collectors.toList());
        SaltPillar pillar = new SaltPillar();
        pillar.add("org_id", minion.getOrg().getId());
        pillar.add("group_ids", groupIds.toArray(new Long[groupIds.size()]));
        pillar.add("addon_group_types",
                addonGroupTypes.toArray(new String[addonGroupTypes.size()]));
        pillar.add("contact_method", minion.getContactMethod().getLabel());
        pillar.add("mgr_server", getChannelHost(minion));
        pillar.add("machine_password", MachinePasswordUtils.machinePassword(minion));

        Map<String, Object> chanPillar = new HashMap<>();
        minion.getAccessTokens().stream().filter(AccessToken::getValid).forEach(accessToken -> {
            accessToken.getChannels().forEach(chan -> {
                Map<String, Object> chanProps = getChannelPillarData(minion, accessToken, chan);

                chanPillar.put(chan.getLabel(), chanProps);
            });
        });
        pillar.add("channels", chanPillar);

        Map<String, Object> beaconConfig = new HashMap<>();
        // this add the configuration for the beacon that tell us when the
        // minion packages are modified locally
        if (minion.getOsFamily().toLowerCase().equals("suse") ||
                minion.getOsFamily().toLowerCase().equals("redhat")) {
            beaconConfig.put("pkgset", PKGSET_BEACON_PROPS);
        }
        // this add the configuration for the beacon that tell us about
        // virtual guests running on that minion
        // TODO: find a better way to detect when the beacon should be configured
        if (minion.isVirtualHost()) {
            beaconConfig.put("virtpoller", VIRTPOLLER_BEACON_PROPS);
        }
        if (!beaconConfig.isEmpty()) {
            pillar.add("beacons", beaconConfig);
        }

        try {
            Files.createDirectories(pillarDataPath);
            Path filePath = pillarDataPath.resolve(
                    getServerPillarFileName(minion)
            );
            com.suse.manager.webui.utils.SaltStateGenerator saltStateGenerator =
                    new com.suse.manager.webui.utils.SaltStateGenerator(filePath.toFile());
            saltStateGenerator.generate(pillar);
        }
        catch (IOException e) {
            LOG.error(e.getMessage(), e);
        }
    }

    /**
     * Create channel pillar data for the given minion, access token and channel.
     * @param minion the minion
     * @param accessToken the access token
     * @param chan the channel
     * @return a {@link Map} containing the pillar data
     */
    public static Map<String, Object> getChannelPillarData(MinionServer minion, AccessToken accessToken, Channel chan) {
        Map<String, Object> chanProps = new HashMap<>();
        chanProps.put("alias", "susemanager:" + chan.getLabel());
        chanProps.put("name", chan.getName());
        chanProps.put("enabled", "1");
        chanProps.put("autorefresh", "1");
        chanProps.put("host", getChannelHost(minion));
        if ("ssh-push-tunnel".equals(minion.getContactMethod().getLabel())) {
            chanProps.put("port", Config.get().getInt("ssh_push_port_https"));
        }
        chanProps.put("token", accessToken.getToken());
        chanProps.put("type", "rpm-md");
        chanProps.put("gpgcheck", "0");
        chanProps.put("repo_gpgcheck", "0");
        chanProps.put("pkg_gpgcheck", chan.isGPGCheck() ? "1" : "0");
        return chanProps;
    }

    /**
     * Return the channel hostname for a given server.
     *
     * @param server server to get the channel host for.
     * @return channel hostname.
     */
    public static String getChannelHost(Server server) {
        Optional<ServerPath> path = server.getFirstServerPath();
        if (!path.isPresent()) {
            // client is not proxied, return this server's hostname
            // HACK: we currently have no better way
            return ConfigDefaults.get().getCobblerHost();
        }
        else {
            return path.get().getHostname();
        }
    }

    private String getServerPillarFileName(MinionServer minion) {
        return PILLAR_DATA_FILE_PREFIX + "_" +
            minion.getMinionId() + "." +
                PILLAR_DATA_FILE_EXT;
    }

    /**
     * Remove the corresponding pillar data if the server is a minion.
     * @param minion the minion server
     */
    public void removePillar(MinionServer minion) {
        LOG.debug("Removing pillar file for minion: " + minion.getMinionId());
        Path filePath = pillarDataPath.resolve(
                getServerPillarFileName(minion));
        try {
            Files.deleteIfExists(filePath);
        }
        catch (IOException e) {
            LOG.error("Could not remove pillar file " + filePath);
        }
    }

    /**
     * Remove the config channel assignments for minion server.
     * @param minion the minion server
     */
    public void removeConfigChannelAssignments(MinionServer minion) {
        removeConfigChannelAssignments(getServerStateFileName(minion.getMachineId()));
    }

    /**
     * Remove the config channel assignments for server group.
     * @param group the server group
     */
    public void removeConfigChannelAssignments(ServerGroup group) {
        removeConfigChannelAssignments(getGroupStateFileName(group.getId()));
    }

    /**
     * Remove the config channel assignments for an organization.
     * @param org the organization
     */
    public void removeConfigChannelAssignments(Org org) {
        removeConfigChannelAssignments(getOrgStateFileName(org.getId()));
    }

    private void removeConfigChannelAssignments(String file) {
        Path baseDir = suseManagerStatesFilesRoot.resolve(SALT_CONFIG_STATES_DIR);
        Path filePath = baseDir.resolve(defaultExtension(file));

        try {
            Files.deleteIfExists(filePath);
        }
        catch (IOException e) {
            LOG.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Generate .sls file to assign config channels to a configurable entity.
     * @param revision the state revision of the configurable
     */
    public void generateConfigState(StateRevision revision) {
        generateConfigState(revision, suseManagerStatesFilesRoot);
    }

    /**
     * Generate .sls file to assign config channels to a configurable entity.
     * @param revision the state revision of the configurable
     * @param statePath the directory where to generate the files
     */
    public void generateConfigState(StateRevision revision, Path statePath) {
        if (revision instanceof ServerStateRevision) {
            generateServerConfigState((ServerStateRevision) revision, statePath);
        }
        else if (revision instanceof ServerGroupStateRevision) {
            generateGroupConfigState((ServerGroupStateRevision) revision, statePath);
        }
        else if (revision instanceof OrgStateRevision) {
            generateOrgConfigState((OrgStateRevision) revision, statePath);
        }
    }

    /**
     * Generate .sls file to assign config channels to a server.
     * @param serverStateRevision the state revision of a server
     * @param statePath the directory where to generate the files
     */
    private void generateServerConfigState(ServerStateRevision serverStateRevision, Path statePath) {
        serverStateRevision.getServer().asMinionServer().ifPresent(minion -> {
            LOG.debug("Generating config channel SLS file for server: " + minion.getId());

            generateConfigStates(serverStateRevision, getServerStateFileName(minion.getMachineId()), statePath);
        });
    }

    /**
     * Generate .sls file to assign config channels to a server group.
     * @param groupStateRevision the state revision of a server group
     * @param statePath the directory where to generate the files
     */
    private void generateGroupConfigState(ServerGroupStateRevision groupStateRevision,
                                         Path statePath) {
        ServerGroup group = groupStateRevision.getGroup();
        LOG.debug("Generating config channel SLS file for server group: " + group.getId());

        generateConfigStates(groupStateRevision, getGroupStateFileName(group.getId()), statePath);
    }

    /**
     * Generate .sls file to assign config channels to an org.
     * @param orgStateRevision the state revision of an org
     * @param statePath the directory where to generate the sls files
     */
    private void generateOrgConfigState(OrgStateRevision orgStateRevision, Path statePath) {
        Org org = orgStateRevision.getOrg();
        LOG.debug("Generating config channel SLS file for organization: " + org.getId());

        generateConfigStates(orgStateRevision, getOrgStateFileName(org.getId()), statePath);
    }

    private void generateConfigStates(StateRevision stateRevision, String fileName, Path statePath) {
        generateStateAssignmentFile(fileName, stateRevision.getConfigChannels(), statePath);
    }

    private void generateStateAssignmentFile(String fileName, List<ConfigChannel> states, Path statePath) {
        ConfigChannelSaltManager confChannelSaltManager =
                ConfigChannelSaltManager.getInstance();
        Path baseDir = statePath.resolve(SALT_CONFIG_STATES_DIR);
        List<String> stateNames =
                states.stream().map(confChannelSaltManager::getChannelStateName).collect(Collectors.toList());
        try {
            Files.createDirectories(baseDir);
            Path filePath = baseDir.resolve(defaultExtension(fileName));
            com.suse.manager.webui.utils.SaltStateGenerator saltStateGenerator =
                    new com.suse.manager.webui.utils.SaltStateGenerator(filePath.toFile());
            saltStateGenerator.generate(new SaltConfigChannelState(stateNames));
        }
        catch (IOException e) {
            LOG.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Remove pillars and config channels assignments of a server.
     * @param minion the minion
     */
    public void removeServer(MinionServer minion) {
        removePillar(minion);
        removeConfigChannelAssignments(minion);
    }

    /**
     * Remove config channels assignments of a group.
     * @param group the group
     */
    public void removeServerGroup(ServerGroup group) {
        removeConfigChannelAssignments(group);
    }

    /**
     * Remove config channels assignments of all servers in that org.
     * @param org the org
     */
    public void removeOrg(Org org) {
        MinionServerFactory.lookupByOrg(org.getId()).forEach(this::removeServer);
        removeConfigChannelAssignments(org);
    }

    /**
     * Regenerate config channel assignments for org, group and severs where
     * the given state is used.
     * @param configChannelIn the config channel
     */
    public void regenerateConfigStates(ConfigChannel configChannelIn) {
        StateFactory.StateRevisionsUsage usage = StateFactory
                .latestStateRevisionsByConfigChannel(configChannelIn);
        regenerateConfigStates(usage);
    }

    /**
     * Regenerate config channel assignments for org, group and severs for
     * the given usages.
     * @param usage config channel usages
     */
    public void regenerateConfigStates(StateFactory.StateRevisionsUsage usage) {
        usage.getServerStateRevisions().forEach(this::generateConfigState);
        usage.getServerGroupStateRevisions().forEach(this::generateConfigState);
        usage.getOrgStateRevisions().forEach(this::generateConfigState);
    }

    /**
     * Regenerate pillar with the new org and create a new state revision without
     * any package or config channels.
     * @param minion the migrated server
     * @param user the user performing the migration
     */
    public void migrateServer(MinionServer minion, User user) {
        // generate a new state revision without any package or config channels
        ServerStateRevision newStateRev = StateRevisionService.INSTANCE
                .cloneLatest(minion, user, false, false);
        StateFactory.save(newStateRev);

        // refresh pillar, config and package states
        generatePillar(minion);
        generateConfigState(newStateRev);
        StatesAPI.generateServerPackageState(minion);
    }

    private String getGroupStateFileName(long groupId) {
        return "group_" + groupId;
    }

    private String getOrgStateFileName(long orgId) {
        return "org_" + orgId;
    }


    private String getServerStateFileName(String digitalServerId) {
        return SALT_SERVER_STATE_FILE_PREFIX + digitalServerId;
    }


    /**
     * @param groupId the id of the server group
     * @return the name of the generated server group .sls file.
     */
    public String getServerGroupGeneratedStateName(long groupId) {
        return SALT_CONFIG_STATES_DIR + "." + getGroupStateFileName(groupId);
    }

    /**
     * @param generatedSlsRootIn the root path where state files are generated
     */
    public void setSuseManagerStatesFilesRoot(Path generatedSlsRootIn) {
        this.suseManagerStatesFilesRoot = generatedSlsRootIn;
    }

    /**
     * @param pillarDataPathIn the root path where pillar files are generated
     */
    public void setPillarDataPath(Path pillarDataPathIn) {
        this.pillarDataPath = pillarDataPathIn;
    }

    /**
     * Generate state files for a new server group.
     * @param serverGroup the new server group
     */
    public void createServerGroup(ServerGroup serverGroup) {
        generateStateAssignmentFile(getGroupStateFileName(serverGroup.getId()), Collections.emptyList(),
                suseManagerStatesFilesRoot);
    }

    /**
     * Generate state files for a new org.
     * @param org the new org
     */
    public void createOrg(Org org) {
        generateStateAssignmentFile(getOrgStateFileName(org.getId()), Collections.emptyList(),
                suseManagerStatesFilesRoot);
    }


}