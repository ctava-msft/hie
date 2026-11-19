targetScope = 'resourceGroup'

@description('Short workload name used in resource names.')
param namePrefix string = 'hie'

@description('Kubernetes/Entra admin group object ID. Members receive AKS RBAC cluster administration.')
param aksAdminGroupObjectId string

@description('Azure region for all resources.')
param location string = resourceGroup().location

@description('AKS system node count.')
@minValue(1)
@maxValue(20)
param aksNodeCount int = 2

@description('AKS system node VM size.')
param aksNodeVmSize string = 'Standard_D4s_v5'

@description('ADX cluster SKU. Use a supported production SKU after the POC.')
param adxSkuName string = 'Dev(No SLA)_Standard_E2a_v4'

var suffix = take(uniqueString(subscription().id, resourceGroup().id, namePrefix), 8)
var stem = '${namePrefix}-${suffix}'
var storageName = '${replace(namePrefix, '-', '')}med${suffix}'
var acrName = '${replace(namePrefix, '-', '')}acr${suffix}'
var eventHubsName = 'ehns-${stem}'
var serviceBusName = 'sb-${stem}'
var aksName = 'aks-${stem}'
var workloadIdentityName = 'id-${stem}-medallion'
var vnetName = 'vnet-${stem}'
var aksSubnetName = 'snet-aks'
var endpointSubnetName = 'snet-private-endpoints'
var adxName = take(replace('kusto${namePrefix}${suffix}', '-', ''), 22)
var eventHubName = 'hie-events'
var databaseName = 'hie_gold'
var storageBlobDataContributor = 'ba92f5b4-2d11-453d-a403-e96b0029c9fe'
var eventHubsDataOwner = 'f526a384-b230-433a-b45c-95f59c4a2f3d'
var serviceBusDataOwner = '090c5cfd-751d-490a-894a-3ce6f1109419'
var acrPull = '7f951dda-4ed3-4680-a7ca-43fe172d538d'
var networkContributor = '4d97b98b-1d4f-4787-a291-c67834d212e7'

resource vnet 'Microsoft.Network/virtualNetworks@2024-05-01' = {
  name: vnetName
  location: location
  properties: {
    addressSpace: {
      addressPrefixes: ['10.80.0.0/16']
    }
    subnets: [
      {
        name: aksSubnetName
        properties: {
          addressPrefix: '10.80.1.0/24'
        }
      }
      {
        name: endpointSubnetName
        properties: {
          addressPrefix: '10.80.2.0/24'
          privateEndpointNetworkPolicies: 'Disabled'
        }
      }
    ]
  }
}

resource logAnalytics 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: 'log-${stem}'
  location: location
  properties: {
    sku: {
      name: 'PerGB2018'
    }
    retentionInDays: 30
    publicNetworkAccessForIngestion: 'Enabled'
    publicNetworkAccessForQuery: 'Enabled'
  }
}

resource acr 'Microsoft.ContainerRegistry/registries@2023-07-01' = {
  name: acrName
  location: location
  sku: {
    name: 'Premium'
  }
  properties: {
    adminUserEnabled: false
    publicNetworkAccess: 'Disabled'
    networkRuleBypassOptions: 'AzureServices'
  }
}

resource workloadIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: workloadIdentityName
  location: location
}

resource aksControlIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: 'id-${stem}-aks-control'
  location: location
}

resource aksNetworkRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(vnet.id, aksControlIdentity.id, networkContributor)
  scope: vnet
  properties: {
    principalId: aksControlIdentity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', networkContributor)
  }
}

resource aks 'Microsoft.ContainerService/managedClusters@2024-10-01' = {
  name: aksName
  location: location
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${aksControlIdentity.id}': {}
    }
  }
  properties: {
    dnsPrefix: stem
    enableRBAC: true
    aadProfile: {
      managed: true
      enableAzureRBAC: true
      adminGroupObjectIDs: [aksAdminGroupObjectId]
    }
    apiServerAccessProfile: {
      enablePrivateCluster: true
    }
    oidcIssuerProfile: {
      enabled: true
    }
    securityProfile: {
      workloadIdentity: {
        enabled: true
      }
    }
    workloadAutoScalerProfile: {
      keda: {
        enabled: true
      }
    }
    agentPoolProfiles: [
      {
        name: 'system'
        count: aksNodeCount
        vmSize: aksNodeVmSize
        mode: 'System'
        osType: 'Linux'
        osSKU: 'Ubuntu'
        type: 'VirtualMachineScaleSets'
        vnetSubnetID: '${vnet.id}/subnets/${aksSubnetName}'
      }
    ]
    networkProfile: {
      networkPlugin: 'azure'
      networkPluginMode: 'overlay'
      networkPolicy: 'cilium'
      networkDataplane: 'cilium'
      loadBalancerSku: 'standard'
      outboundType: 'loadBalancer'
      serviceCidr: '10.81.0.0/16'
      dnsServiceIP: '10.81.0.10'
      podCidr: '10.82.0.0/16'
    }
    addonProfiles: {
      omsAgent: {
        enabled: true
        config: {
          logAnalyticsWorkspaceResourceID: logAnalytics.id
        }
      }
    }
  }
  dependsOn: [
    aksNetworkRole
  ]
}

resource workloadFederation 'Microsoft.ManagedIdentity/userAssignedIdentities/federatedIdentityCredentials@2023-01-31' = {
  parent: workloadIdentity
  name: 'aks-medallion'
  properties: {
    audiences: ['api://AzureADTokenExchange']
    issuer: aks.properties.oidcIssuerProfile.issuerURL
    subject: 'system:serviceaccount:hie-medallion:aks-medallion'
  }
}

resource acrPullRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(acr.id, aks.id, acrPull)
  scope: acr
  properties: {
    principalId: aks.properties.identityProfile.kubeletidentity.objectId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', acrPull)
  }
}

resource storage 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  name: storageName
  location: location
  sku: {
    name: 'Standard_ZRS'
  }
  kind: 'StorageV2'
  properties: {
    isHnsEnabled: true
    accessTier: 'Hot'
    allowBlobPublicAccess: false
    allowSharedKeyAccess: false
    minimumTlsVersion: 'TLS1_2'
    publicNetworkAccess: 'Disabled'
    supportsHttpsTrafficOnly: true
  }
}

resource blobService 'Microsoft.Storage/storageAccounts/blobServices@2023-05-01' = {
  parent: storage
  name: 'default'
  properties: {
    deleteRetentionPolicy: {
      enabled: true
      days: 14
    }
    containerDeleteRetentionPolicy: {
      enabled: true
      days: 14
    }
  }
}

var containers = ['bronze', 'silver', 'quarantine', 'eventhub-checkpoints']
resource medallionContainers 'Microsoft.Storage/storageAccounts/blobServices/containers@2023-05-01' = [
  for containerName in containers: {
    parent: blobService
    name: containerName
    properties: {
      publicAccess: 'None'
    }
  }
]

resource eventHubs 'Microsoft.EventHub/namespaces@2024-01-01' = {
  name: eventHubsName
  location: location
  sku: {
    name: 'Standard'
    tier: 'Standard'
    capacity: 1
  }
  properties: {
    disableLocalAuth: true
    publicNetworkAccess: 'Disabled'
    minimumTlsVersion: '1.2'
  }
}

resource eventHub 'Microsoft.EventHub/namespaces/eventhubs@2024-01-01' = {
  parent: eventHubs
  name: eventHubName
  properties: {
    partitionCount: 4
    messageRetentionInDays: 7
  }
}

resource consumerGroup 'Microsoft.EventHub/namespaces/eventhubs/consumergroups@2024-01-01' = {
  parent: eventHub
  name: 'aks-medallion'
}

resource serviceBus 'Microsoft.ServiceBus/namespaces@2022-10-01-preview' = {
  name: serviceBusName
  location: location
  sku: {
    name: 'Premium'
    tier: 'Premium'
    capacity: 1
  }
  properties: {
    disableLocalAuth: true
    minimumTlsVersion: '1.2'
    publicNetworkAccess: 'Disabled'
    premiumMessagingPartitions: 1
  }
}

var queueNames = ['bronze-ready', 'silver-ready']
resource stageQueues 'Microsoft.ServiceBus/namespaces/queues@2022-10-01-preview' = [
  for queueName in queueNames: {
    parent: serviceBus
    name: queueName
    properties: {
      enableBatchedOperations: true
      deadLetteringOnMessageExpiration: true
      duplicateDetectionHistoryTimeWindow: 'PT30M'
      requiresDuplicateDetection: true
      maxDeliveryCount: 10
      lockDuration: 'PT5M'
      maxSizeInMegabytes: 1024
    }
  }
]

resource adx 'Microsoft.Kusto/clusters@2023-08-15' = {
  name: adxName
  location: location
  sku: {
    name: adxSkuName
    capacity: 1
    tier: startsWith(adxSkuName, 'Dev') ? 'Basic' : 'Standard'
  }
  identity: {
    type: 'SystemAssigned'
  }
  properties: {
    publicNetworkAccess: 'Disabled'
    enablePurge: false
    enableDiskEncryption: true
    enableDoubleEncryption: false
    enableStreamingIngest: true
  }
}

resource adxDatabase 'Microsoft.Kusto/clusters/databases@2023-08-15' = {
  parent: adx
  name: databaseName
  kind: 'ReadWrite'
  location: location
  properties: {
    softDeletePeriod: 'P30D'
    hotCachePeriod: 'P7D'
  }
}

resource adxSchema 'Microsoft.Kusto/clusters/databases/scripts@2023-08-15' = {
  parent: adxDatabase
  name: 'gold-event-schema'
  properties: {
    scriptContent: loadTextContent('./GoldEvent.kql')
    continueOnErrors: false
  }
}

resource adxIngestor 'Microsoft.Kusto/clusters/databases/principalAssignments@2023-08-15' = {
  parent: adxDatabase
  name: guid(adxDatabase.id, workloadIdentity.id, 'ingestor')
  properties: {
    principalId: workloadIdentity.properties.principalId
    principalType: 'App'
    role: 'Ingestor'
    tenantId: tenant().tenantId
  }
}

resource storageRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(storage.id, workloadIdentity.id, storageBlobDataContributor)
  scope: storage
  properties: {
    principalId: workloadIdentity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', storageBlobDataContributor)
  }
}

resource eventHubRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(eventHub.id, workloadIdentity.id, eventHubsDataOwner)
  scope: eventHub
  properties: {
    principalId: workloadIdentity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', eventHubsDataOwner)
  }
}

resource serviceBusRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(serviceBus.id, workloadIdentity.id, serviceBusDataOwner)
  scope: serviceBus
  properties: {
    principalId: workloadIdentity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', serviceBusDataOwner)
  }
}

var privateServices = [
  { name: 'acr', resourceId: acr.id, groupId: 'registry', zone: 'privatelink.azurecr.io' }
  { name: 'blob', resourceId: storage.id, groupId: 'blob', zone: 'privatelink.blob.core.windows.net' }
  { name: 'eventhubs', resourceId: eventHubs.id, groupId: 'namespace', zone: 'privatelink.servicebus.windows.net' }
  { name: 'servicebus', resourceId: serviceBus.id, groupId: 'namespace', zone: 'privatelink.servicebus.windows.net' }
  { name: 'adx', resourceId: adx.id, groupId: 'cluster', zone: 'privatelink.kusto.windows.net' }
]

resource privateDnsZones 'Microsoft.Network/privateDnsZones@2020-06-01' = [
  for service in union(map(privateServices, service => service.zone), []): {
    name: service
    location: 'global'
  }
]

resource dnsLinks 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2020-06-01' = [
  for service in union(map(privateServices, service => service.zone), []): {
    name: '${service}/link-${stem}'
    location: 'global'
    properties: {
      registrationEnabled: false
      virtualNetwork: {
        id: vnet.id
      }
    }
    dependsOn: privateDnsZones
  }
]

resource privateEndpoints 'Microsoft.Network/privateEndpoints@2024-05-01' = [
  for service in privateServices: {
    name: 'pep-${stem}-${service.name}'
    location: location
    properties: {
      subnet: {
        id: '${vnet.id}/subnets/${endpointSubnetName}'
      }
      privateLinkServiceConnections: [
        {
          name: service.name
          properties: {
            privateLinkServiceId: service.resourceId
            groupIds: [service.groupId]
          }
        }
      ]
    }
    dependsOn: privateDnsZones
  }
]

resource privateDnsGroups 'Microsoft.Network/privateEndpoints/privateDnsZoneGroups@2024-05-01' = [
  for (service, index) in privateServices: {
    parent: privateEndpoints[index]
    name: 'default'
    properties: {
      privateDnsZoneConfigs: [
        {
          name: replace(service.zone, '.', '-')
          properties: {
            privateDnsZoneId: resourceId('Microsoft.Network/privateDnsZones', service.zone)
          }
        }
      ]
    }
    dependsOn: privateDnsZones
  }
]

output aksName string = aks.name
output acrLoginServer string = acr.properties.loginServer
output workloadIdentityClientId string = workloadIdentity.properties.clientId
output workloadIdentityPrincipalId string = workloadIdentity.properties.principalId
output eventHubsNamespace string = '${eventHubs.name}.servicebus.windows.net'
output serviceBusNamespace string = '${serviceBus.name}.servicebus.windows.net'
output storageAccountUrl string = storage.properties.primaryEndpoints.blob
output adxIngestionUrl string = adx.properties.uri
output adxDatabase string = databaseName
