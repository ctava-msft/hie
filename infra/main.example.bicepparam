using './main.bicep'

param fabricCapacityId = '00000000-0000-0000-0000-000000000000'
param fabricDeploymentIdentityResourceId = '/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-hie-poc/providers/Microsoft.ManagedIdentity/userAssignedIdentities/id-hie-fabric-deployer'
param workspaceDisplayName = 'hie-gold-poc'
param eventhouseDisplayName = 'hie-gold-eventhouse'
param kqlDatabaseDisplayName = 'hie_gold'
param eventstreamDisplayName = 'hie-gold-fhir-events'
param activatorDisplayName = 'epna-qualified-encounter-alert'
param alertRecipient = 'poc-alert-owner@example.org'
param enableActivatorRule = false
