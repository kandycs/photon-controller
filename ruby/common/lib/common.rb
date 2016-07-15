# Copyright 2015 VMware, Inc. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy of
# the License at http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed
# under the License is distributed on an "AS IS" BASIS, without warranties or
# conditions of any kind, EITHER EXPRESS OR IMPLIED. See the License for the
# specific language governing permissions and limitations under the License.

require "faraday"
require "json"
require "logger"
require "securerandom"
require "set"
require "thread"

require "common/client"
require "common/api_client"
require "common/cmd_runner"
require "common/config"
require "common/go_cli_client"
require "common/vm_disk"
require "common/errors"
require "common/http_response"
require "common/http_client"
require "common/quota_line_item"
require "common/auth_helper"

require "common/resources/auth_info"
require "common/resources/deployment"
require "common/resources/deployment_list"
require "common/resources/portgroup"
require "common/resources/portgroup_list"
require "common/resources/project"
require "common/resources/project_list"
require "common/resources/resource_ticket"
require "common/resources/project_ticket"
require "common/resources/resource_ticket_list"
require "common/resources/step"
require "common/resources/task"
require "common/resources/task_error"
require "common/resources/task_list"
require "common/resources/tenant"
require "common/resources/tenant_list"
require "common/resources/vm"
require "common/resources/vm_list"
require "common/resources/disk"
require "common/resources/disk_list"
require "common/resources/flavor"
require "common/resources/flavor_list"
require "common/resources/network"
require "common/resources/host"
require "common/resources/network_configuration"
require "common/resources/network_connection"
require "common/resources/network_list"
require "common/resources/host_list"
require "common/resources/vm_networks"
require "common/resources/image"
require "common/resources/image_list"
require "common/resources/iso"
require "common/resources/locality"
require "common/resources/migration_status"
require "common/resources/status"
require "common/resources/component_status"
require "common/resources/component_instance"
require "common/resources/mks_ticket"
require "common/resources/cluster"
require "common/resources/cluster_list"
require "common/resources/cluster_configuration"
require "common/resources/security_group"
require "common/resources/stats_info"
require "common/resources/availability_zone"
require "common/resources/availability_zone_list"
require "common/resources/available"
require "common/resources/virtual_network"
require "common/resources/virtual_network_list"

require "common/create_specs/auth_configuration_spec"
require "common/create_specs/deployment_create_spec"
require "common/create_specs/project_create_spec"
require "common/create_specs/resource_ticket_create_spec"
require "common/create_specs/tenant_create_spec"
require "common/create_specs/vm_create_spec"
require "common/create_specs/disk_create_spec"
require "common/create_specs/network_connection_create_spec"
require "common/create_specs/flavor_create_spec"
require "common/create_specs/network_create_spec"
require "common/create_specs/host_create_spec"
require "common/create_specs/image_create_spec"
require "common/create_specs/cluster_create_spec"
require "common/create_specs/cluster_configuration_spec"
require "common/create_specs/availability_zone_create_spec.rb"
require "common/create_specs/host_set_availability_zone_spec.rb"
require "common/create_specs/virtual_network_create_spec.rb"

require "common/importers/ip_range"
require "common/importers/deployment_importer"
require "common/importers/hosts_importer"
