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

require "spec_helper"

describe "flavor" do

  ["vm","persistent-disk","ephemeral-disk"].each do |kind|
    it "should create #{kind} flavor, get it, then delete it" do
      flavor_name = random_name("flavor-")
      flavor_kind = kind
      flavor_cost = [create_limit("vm", 1.0, "COUNT")]
      flavor = create_flavor(EsxCloud::FlavorCreateSpec.new(flavor_name, flavor_kind, flavor_cost))
      flavor.name.should == flavor_name
      flavor.kind.should == flavor_kind

      flavors = find_flavors_by_name_kind(flavor_name, flavor_kind)
      flavors.items.size.should == 1
      flavors.items[0].name.should == flavor_name

      flavor = find_flavor_by_id(flavor.id)
      flavor.name.should == flavor_name
      flavor.kind.should == flavor_kind

      tasks = client.get_flavor_tasks(flavor.id).items
      expect(tasks.size).to eq(1)
      expect(tasks.first.operation).to eq("CREATE_FLAVOR")
      expect(tasks.first.state).to eq("COMPLETED")

      flavor.delete

      flavors = find_flavors_by_name_kind(flavor_name, flavor_kind)
      flavors.items.size.should == 0
    end
  end

  it "should raise exception for undefined flavors" do
    flavor_name = random_name("fake-flavor")
    flavor_kind = "persistent-disk"
    flavors = find_flavors_by_name_kind(flavor_name, flavor_kind)
    flavors.items.size.should == 0

    flavor_id = "fake-flavor-id"
    begin
      find_flavor_by_id(flavor_id)
      fail("Find flavor with id '#{flavor_id}' should fail")
    rescue EsxCloud::ApiError => e
      e.response_code.should == 404
      e.errors.size.should == 1
      e.errors[0].code.should include("FlavorNotFound")
    end
  end

  it "should raise exception for deleting non existed flavors" do
    flavor_name = random_name("fake-flavor")
    flavor_kind = "persistent-disk"
    begin
      delete_flavor_by_name_kind(flavor_name, flavor_kind)
      fail("Flavor delete with name '#{flavor_name}', kind '#{flavor_kind}' should fail")
    rescue EsxCloud::NotFound
    rescue EsxCloud::CliError => e
      e.output.should include("Flavor named '#{flavor_name}', kind '#{flavor_kind}' not found")
    end

    flavor_id = "fake-flavor-id"
    begin
      flavor = delete_flavor_by_id(flavor_id)
      fail("Flavor delete with id #{flavor_id} should fail")
    rescue EsxCloud::ApiError => e
      e.response_code.should == 404
      e.errors.size.should == 1
      e.errors[0].code.should include("FlavorNotFound")
    end
  end

  it "should raise exception for invalid kind flavor" do
    flavor_name = random_name("flavor-")
    flavor_kind = "fake-disk-kind"
    flavor_cost = [create_limit("vm", 1.0, "COUNT")]
    begin
      create_flavor(EsxCloud::FlavorCreateSpec.new(flavor_name, flavor_kind, flavor_cost))
      fail("Flavor create with invalid kind '#{flavor_kind}' should fail")
    rescue EsxCloud::ApiError => e
      e.response_code.should == 400
      e.errors.size.should == 1
      e.errors[0].code.should include("InvalidEntity")
    rescue EsxCloud::CliError => e
      e.output.should include("Invalid kind '#{flavor_kind}'")
    end
  end

  it "should raise exception for duplicate flavor name and kind" do
    flavor_name = random_name("flavor-")
    flavor_kind = "vm"
    flavor_cost = [create_limit("vm", 1.0, "COUNT")]
    create_flavor(EsxCloud::FlavorCreateSpec.new(flavor_name, flavor_kind, flavor_cost))
    begin
      create_flavor(EsxCloud::FlavorCreateSpec.new(flavor_name, flavor_kind, flavor_cost))
      fail("Flavor create with duplicate name and kind should fail")
    rescue EsxCloud::ApiError => e
      e.response_code.should == 400
      e.errors.size.should == 1
      e.errors[0].code.should include("NameTaken")
    rescue EsxCloud::CliError => e
      e.output.should include("name '#{flavor_name}' already taken")
    end
  end

  context "when flavor is in PENDING_DELETE", image: true do
    let(:test_flavor) { EsxCloud::SystemSeeder.instance.pending_delete_vm_flavor! }

    it "should fail to delete PENDING_DELETE flavor" do
      begin
        expect(test_flavor.state).to eq "PENDING_DELETE"

        test_flavor.delete
        fail "delete flavor in PENDING_DELETE state should fail"
      rescue EsxCloud::ApiError => e
        expect(e.response_code).to eq 400
        expect(e.errors.size).to eq 1
        expect(e.errors[0].code).to eq "InvalidFlavorState"
      rescue EsxCloud::CliError => e
        expect(e.message).to match("InvalidFlavorState")
      end
    end
  end

  xit "should list empty flavors, one flavor and multiple flavors" do
    flavors = find_all_flavors()
    flavors.items.size.should == 0

    flavor_name = random_name("flavor-")
    flavor_kind = "vm"
    flavor_cost = [create_limit("vm", 1.0, "COUNT")]
    flavor = create_flavor(EsxCloud::FlavorCreateSpec.new(flavor_name, flavor_kind, flavor_cost))

    tasks = client.get_flavor_tasks(flavor.id).items
    expect(tasks.size).to eq(1)
    expect(tasks.first.operation).to eq("CREATE_FLAVOR")
    expect(tasks.first.state).to eq("COMPLETED")

    flavors = find_all_flavors()
    flavors.items.size.should == 1
    flavors.items[0].name.should == flavor_name

    flavor_name = random_name("flavor-")
    flavor_kind = "persistent-disk"
    flavor = create_flavor(EsxCloud::FlavorCreateSpec.new(flavor_name, flavor_kind, flavor_cost))

    tasks = client.get_flavor_tasks(flavor.id).items
    expect(tasks.size).to eq(1)
    expect(tasks.first.operation).to eq("CREATE_FLAVOR")
    expect(tasks.first.state).to eq("COMPLETED")

    flavors = find_all_flavors()
    flavors.items.size.should == 2

    flavors.items.each do |flavor|
      flavor.delete
    end
  end
end
