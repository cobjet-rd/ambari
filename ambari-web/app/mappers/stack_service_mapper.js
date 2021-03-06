/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
var App = require('app');

App.stackServiceMapper = App.QuickDataMapper.create({
  model: App.StackService,
  component_model: App.StackServiceComponent,

  config: {
    id: 'service_name',
    stack_id: 'stack_id',
    service_name: 'service_name',
    display_name: 'display_name',
    config_types: 'config_types',
    comments: 'comments',
    service_version: 'service_version',
    stack_name: 'stack_name',
    stack_version: 'stack_version',
    is_selected: 'is_selected',
    is_installed: 'is_installed',
    is_installable: 'is_installable',
    required_services: 'required_services',
    service_check_supported: 'service_check_supported',
    service_components_key: 'service_components',
    service_components_type: 'array',
    service_components: {
      item: 'id'
    }
  },

  component_config: {
    id: 'component_name',
    component_name: 'component_name',
    display_name: 'display_name',
    cardinality: 'cardinality',
    custom_commands: 'custom_commands',
    service_name: 'service_name',
    component_category: 'component_category',
    is_master: 'is_master',
    is_client: 'is_client',
    stack_name: 'stack_name',
    stack_version: 'stack_version',
    stack_service_id: 'service_name',
    dependencies_key: 'dependencies',
    dependencies_type: 'array',
    dependencies: {
      item: 'Dependencies'
    }
  },

  mapStackServices: function(json) {
    App.set('isStackServicesLoaded',false);
    this.clearStackModels();
    App.resetDsStoreTypeMap(App.StackServiceComponent);
    App.resetDsStoreTypeMap(App.StackService);
    this.map(json);
    App.set('isStackServicesLoaded',true);
  },

  map: function (json) {
    var model = this.get('model');
    var result = [];
    var stackServiceComponents = [];
    var nonInstallableServices = ['KERBEROS'];
    this.rearrangeServicesForDisplayOrder(json.items, App.StackService.displayOrder);
    json.items.forEach(function (item) {
      var stackService = item.StackServices;
      var serviceComponents = [];
      //TODO iterate over item.components after API is fixed
      var components = Em.get(item, 'components') || Em.get(item, 'serviceComponents');
      components.forEach(function (serviceComponent) {
        var dependencies = serviceComponent.dependencies.map(function (dependecy) {
          return { Dependencies: App.keysUnderscoreToCamelCase(App.permit(dependecy.Dependencies, ['component_name', 'scope'])) };
        });
        serviceComponent.StackServiceComponents.id = serviceComponent.StackServiceComponents.component_name;
        serviceComponent.StackServiceComponents.dependencies = dependencies;
        serviceComponents.push(serviceComponent.StackServiceComponents);
        var parsedResult = this.parseIt(serviceComponent.StackServiceComponents, this.get('component_config'));
        if (parsedResult.id == 'MYSQL_SERVER') {
          parsedResult.custom_commands = parsedResult.custom_commands.without('CLEAN');
        }
        stackServiceComponents.push(parsedResult);
      }, this);
      stackService.stack_id = stackService.stack_name + '-' + stackService.stack_version;
      stackService.service_components = serviceComponents;
      // @todo: replace with server response value after API implementation
      if (nonInstallableServices.contains(stackService.service_name)) {
        stackService.is_installable = false;
        stackService.is_selected = false;
      }
      result.push(this.parseIt(stackService, this.get('config')));
    }, this);
    App.store.loadMany(this.get('component_model'), stackServiceComponents);
    App.store.loadMany(model, result);
  },

  /**
   * Clean store from already loaded data.
   **/
  clearStackModels: function () {
    var models = [App.StackServiceComponent, App.StackService];
    models.forEach(function (model) {
      var records = App.get('store').findAll(model).filterProperty('id');
      records.forEach(function (rec) {
        Ember.run(this, function () {
          rec.deleteRecord();
          App.store.commit();
        });
      }, this);
    }, this);
  },

  rearrangeServicesForDisplayOrder: function (array, displayOrderArray) {
    return array.sort(function (a, b) {
      var aValue = displayOrderArray.indexOf(a.StackServices.service_name) != -1 ? displayOrderArray.indexOf(a.StackServices.service_name) : array.length;
      var bValue = displayOrderArray.indexOf(b.StackServices.service_name) != -1 ? displayOrderArray.indexOf(b.StackServices.service_name) : array.length;
      return aValue - bValue;
    });
  }
});

