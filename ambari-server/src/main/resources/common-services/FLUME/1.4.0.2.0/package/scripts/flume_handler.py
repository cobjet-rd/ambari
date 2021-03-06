"""
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

"""

import flume_upgrade

from flume import flume
from flume import get_desired_state

from resource_management import *
from resource_management.libraries.functions.flume_agent_helper import find_expected_agent_names
from resource_management.libraries.functions.flume_agent_helper import get_flume_status
from resource_management.libraries.functions.version import compare_versions, format_hdp_stack_version

class FlumeHandler(Script):

  def get_stack_to_component(self):
    return {"HDP": "flume-server"}

  def install(self, env):
    import params

    self.install_packages(env)
    env.set_params(params)

  def start(self, env, rolling_restart=False):
    import params

    env.set_params(params)
    self.configure(env)

    flume(action='start')

  def stop(self, env, rolling_restart=False):
    import params

    env.set_params(params)

    flume(action='stop')

    if rolling_restart:
      flume_upgrade.post_stop_backup()

  def configure(self, env):
    import params

    env.set_params(params)

    flume(action='config')

  def status(self, env):
    import params
    env.set_params(params)


    processes = get_flume_status(params.flume_conf_dir, params.flume_run_dir)
    expected_agents = find_expected_agent_names(params.flume_conf_dir)

    json = {}
    json['processes'] = processes
    self.put_structured_out(json)

    # only throw an exception if there are agents defined and there is a 
    # problem with the processes; if there are no agents defined, then 
    # the service should report STARTED (green) ONLY if the desired state is started.  otherwise, INSTALLED (red)
    if len(expected_agents) > 0:
      for proc in processes:
        if not proc.has_key('status') or proc['status'] == 'NOT_RUNNING':
          raise ComponentIsNotRunning()
    elif len(expected_agents) == 0 and 'INSTALLED' == get_desired_state():
      raise ComponentIsNotRunning()

  def pre_rolling_restart(self, env):
    import params
    env.set_params(params)

    # this function should not execute if the version can't be determined or
    # is not at least HDP 2.2.0.0
    if not params.version or compare_versions(format_hdp_stack_version(params.version), '2.2.0.0') < 0:
      return

    Logger.info("Executing Flume Rolling Upgrade pre-restart")
    Execute(format("hdp-select set flume-server {version}"))
    flume_upgrade.pre_start_restore()

if __name__ == "__main__":
  FlumeHandler().execute()
