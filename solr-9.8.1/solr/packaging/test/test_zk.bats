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

setup_file() {
  common_clean_setup
  solr start -DminStateByteLenForCompression=0 -c
}

teardown_file() {
  common_setup
  solr stop --all
}

setup() {
  common_setup
}

teardown() {
  # save a snapshot of SOLR_HOME for failed tests
  save_home_on_failure
}

@test "short help" {
 run solr zk ls -h
 assert_output --partial "usage: bin/solr zk"
}

@test "short help is inferred" {
 run solr zk ls
 assert_output --partial "usage: bin/solr zk"
}

@test "long help" {
 run solr zk -h
 assert_output --partial "bin/solr zk ls"
 assert_output --partial "bin/solr zk updateacls"
 assert_output --partial "Pass --help or -h after any COMMAND"
}

@test "running subcommands with zk is prevented" {
 run solr ls / -z localhost:${ZK_PORT}
 assert_output --partial "You must invoke this subcommand using the zk command"
}

@test "listing out files" {
  sleep 1
  run solr zk ls / -z localhost:${ZK_PORT} --recursive
  assert_output --partial "aliases.json"
}

@test "connecting to solr via various solr urls and zk hosts" {
  sleep 1
  run solr zk ls / -solrUrl http://localhost:${SOLR_PORT}/solr
  assert_output --partial "aliases.json"
  # We do mapping in bin/solr script from -solrUrl to --solr-url that prevents deprecation warning
  #assert_output --partial "Deprecated for removal since 9.7: Use --solr-url instead"

  run solr zk ls / -url http://localhost:${SOLR_PORT}
  assert_output --partial "aliases.json"
  # We do mapping in bin/solr script from -solrUrl to --solr-url that prevents deprecation warning
  #assert_output --partial "Deprecated for removal since 9.7: Use --solr-url instead"

  run solr zk ls / --solr-url http://localhost:${SOLR_PORT}
  assert_output --partial "aliases.json"

  run solr zk ls /
  assert_output --partial "aliases.json"

  run solr zk ls / -z localhost:${ZK_PORT}
  assert_output --partial "aliases.json"

  run solr zk ls / --zk-host localhost:${ZK_PORT}
  assert_output --partial "aliases.json"

  run solr zk ls / -zkHost localhost:${ZK_PORT}
  assert_output --partial "aliases.json"
  # We do mapping in bin/solr script from -zkHost to --zk-host that prevents deprecation warning
  #assert_output --partial "Deprecated for removal since 9.7: Use --zk-host instead"
}

@test "copying files around" {
  touch myfile.txt

  run solr zk cp myfile.txt zk:/myfile.txt -z localhost:${ZK_PORT}
  assert_output --partial "Copying from 'myfile.txt' to 'zk:/myfile.txt'. ZooKeeper at localhost:${ZK_PORT}"
  sleep 1
  run solr zk ls / -z localhost:${ZK_PORT}
  assert_output --partial "myfile.txt"

  touch myfile2.txt
  run solr zk cp myfile2.txt zk:myfile2.txt -z localhost:${ZK_PORT}
  sleep 1
  run solr zk ls / -z localhost:${ZK_PORT} --verbose
  assert_output --partial "myfile2.txt"

  touch myfile3.txt
  run solr zk cp myfile3.txt zk:/myfile3.txt -z localhost:${ZK_PORT}
  assert_output --partial "Copying from 'myfile3.txt' to 'zk:/myfile3.txt'. ZooKeeper at localhost:${ZK_PORT}"
  sleep 1
  run solr zk ls / -z localhost:${ZK_PORT}
  assert_output --partial "myfile3.txt"
  
  run solr zk cp zk:/ -r "${BATS_TEST_TMPDIR}/recursive_download/"
  [ -e "${BATS_TEST_TMPDIR}/recursive_download/myfile.txt" ]
  [ -e "${BATS_TEST_TMPDIR}/recursive_download/myfile2.txt" ]
  [ -e "${BATS_TEST_TMPDIR}/recursive_download/myfile3.txt" ]

  rm myfile.txt
  rm myfile2.txt
  rm myfile3.txt
}

@test "upconfig" {
  local source_configset_dir="${SOLR_TIP}/server/solr/configsets/sample_techproducts_configs"
  test -d $source_configset_dir

  run solr zk upconfig -d ${source_configset_dir} -n techproducts2 -z localhost:${ZK_PORT}
  assert_output --partial "Uploading"
  refute_output --partial "ERROR"

  sleep 1
  run curl "http://localhost:${SOLR_PORT}/api/cluster/configs?omitHeader=true"
  assert_output --partial '"configSets":["_default","techproducts2"]'
}

@test "downconfig" {
  run solr zk downconfig -z localhost:${ZK_PORT} -n _default -d "${BATS_TEST_TMPDIR}/downconfig"
  assert_output --partial "Downloading"
  refute_output --partial "ERROR"
}

@test "zkcli.sh gets 'solrhome' from 'solr.home' system property" {
  sleep 1
  run "${SOLR_TIP}/server/scripts/cloud-scripts/zkcli.sh" --verbose
  local extracted_solrhome=$(echo "$output" | grep -oE "solrhome=[^ ]+")
  # remove 'solrhome='
  local path_value=${extracted_solrhome#*=}
  [[ $path_value == *"/server/scripts/cloud-scripts/../../solr" ]] || [[ $path_value == *"/server/solr" ]]
}

@test "zkcli.sh gets 'solrhome' from 'solrhome' command line option" {
  sleep 1
  run "${SOLR_TIP}/server/scripts/cloud-scripts/zkcli.sh" --verbose -s /path/to/solr/home
  local extracted_solrhome=$(echo "$output" | grep -oE "solrhome=[^ ]+")
  # remove 'solrhome='
  local path_value=${extracted_solrhome#*=}
  [[ $path_value == "/path/to/solr/home" ]]
}


@test "bin/solr zk cp gets 'solrhome' from '--solr-home' command line option" {
  touch afile.txt

  run solr zk cp afile.txt zk:/afile.txt -z localhost:${ZK_PORT} --verbose --solr-home ${SOLR_TIP}/server/solr
  assert_output --partial "Using SolrHome: ${SOLR_TIP}/server/solr"
  refute_output --partial 'Failed to load solr.xml from ZK or SolrHome'
  
  # The -DminStateByteLenForCompression variable substitution on solr start is not seen
  # by the ZkCpTool.java, so therefore we do not have compression unless solr.xml is directly edited.
  #assert_output --partial 'Compression of state.json has been enabled'

  rm afile.txt
}
