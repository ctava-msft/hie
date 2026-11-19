using './main.bicep'

param namePrefix = 'hie'
param aksAdminGroupObjectId = 'REPLACE_WITH_ENTRA_GROUP_OBJECT_ID'
param location = 'eastus2'
param aksNodeCount = 2
param aksNodeVmSize = 'Standard_D4s_v5'
param adxSkuName = 'Dev(No SLA)_Standard_E2a_v4'
