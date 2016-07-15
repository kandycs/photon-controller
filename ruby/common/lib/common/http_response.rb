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

module EsxCloud
  class HttpResponse

    attr_reader :code, :body, :headers

    # @param [Fixnum] code
    # @param [String] body
    # @param [Hash] headers
    def initialize(code, body, headers)
      @code = code
      @body = body

      # TODO(olegs): We should probably massage headers.
      @headers = headers
    end

    # @param [#to_s] header_name
    # @return [String]
    def get_header(header_name)
      @headers[header_name.to_s]
    end

  end
end