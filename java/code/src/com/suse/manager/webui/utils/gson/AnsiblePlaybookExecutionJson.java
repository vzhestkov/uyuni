/**
 * Copyright (c) 2021 SUSE LLC
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

package com.suse.manager.webui.utils.gson;

import org.apache.commons.lang3.builder.ToStringBuilder;

import java.util.Date;
import java.util.Optional;

/**
 * DTO for identifying ansible playbook execution
 */
public class AnsiblePlaybookExecutionJson {

    private String playbookPath;
    private Optional<String> inventoryPath = Optional.empty();
    private long controlNodeId;
    private Optional<Date> earliest = Optional.empty();

    /**
     * Gets the playbookPath.
     *
     * @return playbookPath
     */
    public String getPlaybookPath() {
        return playbookPath;
    }

    /**
     * Gets the inventoryPath.
     *
     * @return inventoryPath
     */
    public Optional<String> getInventoryPath() {
        return inventoryPath;
    }

    /**
     * Gets the controlNodeId.
     *
     * @return controlNodeId
     */
    public long getControlNodeId() {
        return controlNodeId;
    }

    /**
     * Gets the earliest.
     *
     * @return earliest
     */
    public Optional<Date> getEarliest() {
        return earliest;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("playbookPath", playbookPath)
                .append("inventoryPath", inventoryPath)
                .append("controlNodeId", controlNodeId)
                .append("earliest", earliest)
                .toString();
    }
}
