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

require_relative "../spec_helper"

describe EsxCloud::ProjectList do

  it "can be created from JSON" do
    projects = (1..3).map do |i|
      {
          "id" => "id-#{i}",
          "name" => "name-#{i}",
          "resourceTicket" => {
              "tenantTicketId" => "some-tenant-ticket-id",
              "tenantTicketName" => "some-tenant-ticket-name",
              "usage" => [],
              "limits" => []}
      }
    end

    json = JSON.generate({"items" => projects})
    list = EsxCloud::ProjectList.create_from_json(json)

    list.items.each_with_index do |item, i|
      item.should == EsxCloud::Project.create_from_hash(projects[i])
    end
  end

  it "needs a proper payload format" do
    expect { EsxCloud::ProjectList.create_from_json("[]") }.to raise_error(EsxCloud::UnexpectedFormat)
  end

end
