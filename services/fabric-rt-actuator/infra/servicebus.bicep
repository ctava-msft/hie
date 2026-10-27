@description('Globally unique Service Bus namespace name.')
param namespaceName string
param location string = resourceGroup().location
param queueName string = 'activator-deliveries'

resource serviceBus 'Microsoft.ServiceBus/namespaces@2024-01-01' = {
  name: namespaceName
  location: location
  sku: {
    name: 'Standard'
    tier: 'Standard'
  }
  properties: {
    minimumTlsVersion: '1.2'
    disableLocalAuth: true
  }
}

resource queue 'Microsoft.ServiceBus/namespaces/queues@2024-01-01' = {
  parent: serviceBus
  name: queueName
  properties: {
    requiresDuplicateDetection: true
    duplicateDetectionHistoryTimeWindow: 'P7D'
    lockDuration: 'PT30S'
    maxDeliveryCount: 10
    defaultMessageTimeToLive: 'P14D'
    deadLetteringOnMessageExpiration: true
    enablePartitioning: false
    maxSizeInMegabytes: 1024
  }
}

output fullyQualifiedNamespace string = '${serviceBus.name}.servicebus.windows.net'
output queueResourceId string = queue.id
