#!/usr/bin/env bats

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

load bats_helper

setup() {
  common_clean_setup
}

@test "solr help flag prints help" {
  run -1 solr --help
  assert_output --partial 'Usage: solr COMMAND OPTIONS'
  refute_output --partial 'ERROR'
}

@test "start help flag prints help" {
  # Test old syntax -help
  run solr start -help
  assert_output --partial 'Usage: solr start'
  refute_output --partial 'ERROR'
}

@test "stop help flag prints help" {
  run solr stop --help
  assert_output --partial 'Usage: solr stop'
  refute_output --partial 'ERROR'
}

@test "restart help flag prints help" {
  run solr restart --help
  assert_output --partial 'Usage: solr restart'
  refute_output --partial 'ERROR'
}

@test "status help flag prints help" {
  run solr status --help
  assert_output --partial 'usage: bin/solr status'
  refute_output --partial 'ERROR'
}

@test "healthcheck help flag prints help" {
  run solr healthcheck --help
  assert_output --partial 'usage: bin/solr healthcheck'
  refute_output --partial 'ERROR'
}

@test "create help flag prints help" {
  run solr create --help
  assert_output --partial 'usage: bin/solr create'
  refute_output --partial 'ERROR'
}

@test "delete help flag prints help" {
  run solr delete -h
  assert_output --partial 'usage: bin/solr delete'
  refute_output --partial 'ERROR'
}

@test "zk help flag prints help" {
  run solr zk --help
  assert_output --partial 'usage:'
  assert_output --partial 'bin/solr zk ls'
  refute_output --partial 'ERROR'
}

@test "auth help flag prints help" {
  run solr auth --help
  assert_output --partial 'bin/solr auth enable'
  refute_output --partial 'ERROR'
}

@test "assert help flag prints help" {
  run solr assert --help
  assert_output --partial 'usage: bin/solr assert'
  refute_output --partial 'ERROR'
}

@test "post help flag prints help" {
  run solr post --help
  assert_output --partial 'usage: bin/solr post'
  refute_output --partial 'ERROR'
}
