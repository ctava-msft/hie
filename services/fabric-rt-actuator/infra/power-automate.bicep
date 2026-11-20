targetScope = 'resourceGroup'

@description('Existing API Management service in this resource group. The root Terraform stack can provision it.')
param apimServiceName string

@minLength(1)
@description('DNS host of the Java HTTPS ingress, reachable from API Management and using a trusted certificate. Do not include a scheme, port, path, query, or credentials.')
param backendHost string

@secure()
@minLength(32)
@description('The Java WEBHOOK_API_KEY, not the admin key. Supply securely, preferably through a Key Vault deployment-parameter reference.')
param webhookApiKey string

resource apim 'Microsoft.ApiManagement/service@2024-05-01' existing = {
  name: apimServiceName
}

resource webhookKey 'Microsoft.ApiManagement/service/namedValues@2024-05-01' = {
  parent: apim
  name: 'fabric-activator-webhook-key'
  properties: {
    displayName: 'fabric-activator-webhook-key'
    secret: true
    value: webhookApiKey
  }
}

resource intakeApi 'Microsoft.ApiManagement/service/apis@2024-05-01' = {
  parent: apim
  name: 'fabric-activator-actions'
  properties: {
    displayName: 'Fabric Activator action intake'
    description: 'Power Automate intake only. No rule-management or health endpoints are exposed.'
    apiRevision: '1'
    apiType: 'http'
    path: 'activator'
    protocols: [
      'https'
    ]
    serviceUrl: 'https://${backendHost}'
    subscriptionRequired: true
    subscriptionKeyParameterNames: {
      header: 'Ocp-Apim-Subscription-Key'
      query: 'subscription-key'
    }
    format: 'swagger-json'
    value: loadTextContent('../power-automate/connector.swagger.json')
  }
}

resource intakePolicy 'Microsoft.ApiManagement/service/apis/policies@2024-05-01' = {
  parent: intakeApi
  name: 'policy'
  properties: {
    format: 'rawxml'
    value: loadTextContent('power-automate-policy.xml')
  }
  dependsOn: [
    webhookKey
  ]
}

resource flowSubscription 'Microsoft.ApiManagement/service/subscriptions@2024-05-01' = {
  parent: apim
  name: 'fabric-activator-power-automate'
  properties: {
    displayName: 'Power Automate Activator intake'
    scope: intakeApi.id
    state: 'active'
    allowTracing: false
  }
}

output gatewayUrl string = apim.properties.gatewayUrl
output actionUrl string = '${apim.properties.gatewayUrl}/activator/actions'
output subscriptionResourceId string = flowSubscription.id
