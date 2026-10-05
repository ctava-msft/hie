targetScope = 'resourceGroup'

@description('The Fabric capacity UUID shown in the Fabric admin portal. This is not the Azure resource ID.')
param fabricCapacityId string

@description('Resource ID of a pre-authorized user-assigned managed identity used to call the Fabric REST API.')
param fabricDeploymentIdentityResourceId string

@description('Display name for the Gold Fabric workspace.')
param workspaceDisplayName string = 'hie-gold-poc'

@description('Display name for the Gold Eventhouse.')
param eventhouseDisplayName string = 'hie-gold-eventhouse'

@description('Display name for the Gold KQL database.')
param kqlDatabaseDisplayName string = 'hie_gold'

@description('Display name for the ePNA Activator item.')
param activatorDisplayName string = 'epna-qualified-encounter-alert'

@description('Email address that receives the proof-of-concept alert.')
param alertRecipient string

@description('Enables the Activator rule. Leave false until the synthetic workflow and clinical governance review pass.')
param enableActivatorRule bool = false

@description('Forces the deployment script to run again when its value changes.')
param forceUpdateTag string = utcNow()

resource fabricBootstrap 'Microsoft.Resources/deploymentScripts@2023-08-01' = {
  name: 'deploy-hie-gold-fabric-poc'
  location: resourceGroup().location
  kind: 'AzurePowerShell'
  identity: {
    type: 'userAssigned'
    userAssignedIdentities: {
      '${fabricDeploymentIdentityResourceId}': {}
    }
  }
  properties: {
    azPowerShellVersion: '12.0'
    cleanupPreference: 'OnSuccess'
    retentionInterval: 'P1D'
    timeout: 'PT45M'
    forceUpdateTag: forceUpdateTag
    scriptContent: loadTextContent('./scripts/deploy-fabric.ps1')
    environmentVariables: [
      {
        name: 'FABRIC_CAPACITY_ID'
        value: fabricCapacityId
      }
      {
        name: 'WORKSPACE_DISPLAY_NAME'
        value: workspaceDisplayName
      }
      {
        name: 'EVENTHOUSE_DISPLAY_NAME'
        value: eventhouseDisplayName
      }
      {
        name: 'KQL_DATABASE_DISPLAY_NAME'
        value: kqlDatabaseDisplayName
      }
      {
        name: 'ACTIVATOR_DISPLAY_NAME'
        value: activatorDisplayName
      }
      {
        name: 'ALERT_RECIPIENT'
        value: alertRecipient
      }
      {
        name: 'ENABLE_ACTIVATOR_RULE'
        value: string(enableActivatorRule)
      }
      {
        name: 'KQL_DATABASE_SCHEMA_BASE64'
        value: base64(loadTextContent('../fabric/kql/DatabaseSchema.kql'))
      }
      {
        name: 'REFLEX_ENTITIES_TEMPLATE_BASE64'
        value: base64(loadTextContent('../fabric/activator/ReflexEntities.template.json'))
      }
    ]
  }
}

output workspaceId string = fabricBootstrap.properties.outputs.workspaceId
output eventhouseId string = fabricBootstrap.properties.outputs.eventhouseId
output kqlDatabaseId string = fabricBootstrap.properties.outputs.kqlDatabaseId
output activatorId string = fabricBootstrap.properties.outputs.activatorId
output activatorRuleEnabled bool = enableActivatorRule
