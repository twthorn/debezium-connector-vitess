#!/bin/bash

# Copyright 2019 The Vitess Authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

hostname=$(hostname -f)
vtctld_web_port=15000
export VTDATAROOT="${VTDATAROOT:-${PWD}/vtdataroot}"

function fail() {
  echo "ERROR: $1"
  exit 1
}

if [[ $EUID -eq 0 ]]; then
  fail "This script refuses to be run as root. Please switch to a regular user."
fi

# mysqld might be in /usr/sbin which will not be in the default PATH
PATH="/usr/sbin:$PATH"
for binary in mysqld etcd etcdctl curl vtctlclient vttablet vtgate vtctld mysqlctl; do
  command -v "$binary" > /dev/null || fail "${binary} is not installed in PATH. See https://vitess.io/docs/get-started/local/ for install instructions."
done;

ETCD_SERVER="localhost:2379"
TOPOLOGY_FLAGS="--topo_implementation etcd2 --topo_global_server_address $ETCD_SERVER --topo_global_root /vitess/global"
mkdir -p "${VTDATAROOT}/etcd"

mkdir -p "${VTDATAROOT}/tmp"

# Set aliases to simplify instructions.
# In your own environment you may prefer to use config files,
# such as ~/.my.cnf

alias mysql="command mysql -h 127.0.0.1 -P 15306"
alias vtctlclient="command vtctlclient --server localhost:15999 --log_dir ${VTDATAROOT}/tmp --alsologtostderr"
alias vtctldclient="command vtctldclient --server localhost:15999 --log_dir ${VTDATAROOT}/tmp --alsologtostderr"

# Make sure aliases are expanded in non-interactive shell
shopt -s expand_aliases
