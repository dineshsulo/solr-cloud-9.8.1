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
  solr start -c -Dsolr.modules=extraction
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

@test "Check help command" {

  run solr post
  assert_output --partial 'Must specify either --solr-update-url or -c parameter'

  run solr post -h
  assert_output --partial 'usage: bin/solr post'
  refute_output --partial 'ERROR'

  run solr post --help
  assert_output --partial 'usage: bin/solr post'
  refute_output --partial 'ERROR'

}


@test "basic post with a type specified" {

  run solr create -c monitors -d _default
  assert_output --partial "Created collection 'monitors'"

  run solr post --type application/xml --solr-update-url http://localhost:${SOLR_PORT}/solr/monitors/update ${SOLR_TIP}/example/exampledocs/monitor.xml

  assert_output --partial '1 files indexed.'
  refute_output --partial 'ERROR'
}

@test "basic post with collection instead of url parameter" {

  run solr create -c monitors_c_param -d _default
  assert_output --partial "Created collection 'monitors_c_param'"

  run solr post --type application/xml -c monitors_c_param ${SOLR_TIP}/example/exampledocs/monitor.xml

  assert_output --partial '1 files indexed.'
  refute_output --partial 'ERROR'
}

@test "basic post with solr-url and collection" {

  run solr create -c monitors_solr_url_param -d _default
  assert_output --partial "Created collection 'monitors_solr_url_param'"

  run solr post --type application/xml -c monitors_solr_url_param --solr-url http://localhost:${SOLR_PORT} ${SOLR_TIP}/example/exampledocs/monitor.xml

  assert_output --partial '1 files indexed.'
  refute_output --partial 'ERROR'
}

@test "basic post WITHOUT a type specified" {

  solr create -c monitors_no_type -d _default

  run solr post --solr-update-url http://localhost:${SOLR_PORT}/solr/monitors_no_type/update ${SOLR_TIP}/example/exampledocs/monitor.xml

  assert_output --partial '1 files indexed.'
  refute_output --partial 'ERROR'
  run curl "http://localhost:${SOLR_PORT}/solr/monitors_no_type/select?q=*:*"
  assert_output --partial '"numFound":1'

  solr create -c books_no_type -d _default

  run solr post --solr-update-url http://localhost:${SOLR_PORT}/solr/books_no_type/update ${SOLR_TIP}/example/exampledocs/books.json

  assert_output --partial '1 files indexed.'
  refute_output --partial 'ERROR'
  run curl "http://localhost:${SOLR_PORT}/solr/books_no_type/select?q=*:*"
  assert_output --partial '"numFound":4'

  solr create -c books_csv_no_type -d _default

  run solr post --solr-update-url http://localhost:${SOLR_PORT}/solr/books_csv_no_type/update ${SOLR_TIP}/example/exampledocs/books.csv

  assert_output --partial '1 files indexed.'
  refute_output --partial 'ERROR'
  run curl "http://localhost:${SOLR_PORT}/solr/books_csv_no_type/select?q=*:*"
  assert_output --partial '"numFound":10'
}

@test "crawling a directory as a dry-run" {

  # We filter to xml,json,and csv as we don't want to invoke the Extract handler, and are running it as a dry run
  run solr post --dry-run --filetypes xml,json,csv --solr-update-url http://localhost:${SOLR_PORT}/solr/foobar/update --skip-commit ${SOLR_TIP}/example/exampledocs

  assert_output --partial 'Dry run complete. 15 would have been indexed.'
  refute_output --partial '15 files indexed.'
  refute_output --partial 'ERROR'
}

@test "crawling a directory" {

  solr create -c mixed_content -d _default

  # We filter to xml,json,and csv as we don't want to invoke the Extract handler.
  run solr post --filetypes xml,json,csv --solr-update-url http://localhost:${SOLR_PORT}/solr/mixed_content/update ${SOLR_TIP}/example/exampledocs

  assert_output --partial '15 files indexed.'
  refute_output --partial 'ERROR'
  run curl "http://localhost:${SOLR_PORT}/solr/mixed_content/select?q=*:*"
  assert_output --partial '"numFound":45'
}

# this test doesn't complete due to issues in posting to the /extract handler
@test "crawling a web site" {
  solr create -c webcrawl -d _default

  curl -X POST -H 'Content-type:application/json' -d '{
    "add-requesthandler": {
      "name": "/update/extract",
      "class": "solr.extraction.ExtractingRequestHandler",
      "defaults":{ "lowernames": "true", "captureAttr":"true"}
    }
  }' "http://localhost:${SOLR_PORT}/solr/webcrawl/config"

  run solr post --mode web --solr-update-url http://localhost:${SOLR_PORT}/webcrawl/update --recursive 1 --delay 1 https://solr.apache.org
  assert_output --partial 'Entering crawl at level 0'
}

@test "skipcommit and optimize and delete" {

  run solr create -c monitors2 -d _default
  assert_output --partial "Created collection 'monitors2'"

  run solr post --solr-update-url http://localhost:${SOLR_PORT}/solr/monitors2/update --type application/xml --skip-commit --optimize ${SOLR_TIP}/example/exampledocs/monitor.xml

  assert_output --partial '1 files indexed.'
  refute_output --partial 'COMMITting Solr index'
  assert_output --partial 'Performing an OPTIMIZE'
  refute_output --partial 'ERROR'

}

@test "args mode" {

  run solr create -c test_args -d _default
  assert_output --partial "Created collection 'test_args'"

  run solr post --solr-update-url http://localhost:${SOLR_PORT}/solr/test_args/update --mode args --type application/xml --out "<delete><query>*:*</query></delete>"
  assert_output --partial '<int name="status">0</int>'

  # confirm default type
  run solr post --solr-update-url http://localhost:${SOLR_PORT}/solr/test_args/update --mode args --out "{'delete': {'query': '*:*'}}"
  assert_output --partial '"status":0'

  # confirm we don't get back output without --out
  run solr post --solr-update-url http://localhost:${SOLR_PORT}/solr/test_args/update --mode args "{'delete': {'query': '*:*'}}"
  refute_output --partial '"status":0'

  run solr post --solr-update-url http://localhost:${SOLR_PORT}/solr/test_args/update --mode args --type text/csv --out $'id,value\nROW1,0.47'
  assert_output --partial '"status":0'
  run curl "http://localhost:${SOLR_PORT}/solr/test_args/select?q=id:ROW1"
  assert_output --partial '"numFound":1'
}

# function used because run echo | solr ends up being (run echo) | solr and we loose the output capture.
capture_echo_to_solr() {
  echo "{'commit': {}}" | solr post --solr-update-url http://localhost:${SOLR_PORT}/solr/test_stdin/update --mode stdin --type application/json --out
}

@test "stdin mode" {

  run solr create -c test_stdin -d _default
  assert_output --partial "Created collection 'test_stdin'"

  run capture_echo_to_solr
  assert_output --partial '"status":0'
}

@test "verbose echo the Solr response" {

  run solr create -c monitors_verbose -d _default
  assert_output --partial "Created collection 'monitors_verbose'"

  run solr post --verbose --type application/xml --solr-update-url http://localhost:${SOLR_PORT}/solr/monitors_verbose/update ${SOLR_TIP}/example/exampledocs/monitor.xml

  assert_output --partial '1 files indexed.'
  assert_output --partial '<lst name="responseHeader">'
  refute_output --partial 'ERROR'
}
