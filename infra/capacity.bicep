targetScope = 'resourceGroup'

@description('Capacity resource name: 3-63 lowercase letters/digits, beginning with a letter.')
@minLength(3)
@maxLength(63)
param capacityName string = 'hiegold${uniqueString(resourceGroup().id)}'

@description('A Fabric-supported Azure region with sufficient capacity-unit quota for the selected SKU.')
param location string = resourceGroup().location

@description('Paid Fabric capacity SKU. F2 is the smallest size; validate workload sizing before increasing it.')
@allowed([
  'F2'
  'F4'
  'F8'
  'F16'
  'F32'
  'F64'
  'F128'
  'F256'
  'F512'
  'F1024'
  'F2048'
])
param capacitySku string = 'F2'

@description('UPNs of tenant-member users who will administer the capacity. Grant deployment-identity access separately in Fabric.')
@minLength(1)
param capacityAdministratorUpns string[]

resource capacity 'Microsoft.Fabric/capacities@2023-11-01' = {
  name: capacityName
  location: location
  sku: {
    name: capacitySku
    tier: 'Fabric'
  }
  properties: {
    administration: {
      members: capacityAdministratorUpns
    }
  }
}

@description('Azure resource ID for managing the capacity. This is NOT the Fabric capacity UUID required by main.bicep.')
output capacityResourceId string = capacity.id
output capacityResourceName string = capacity.name
output capacityLocation string = capacity.location
output deployedCapacitySku string = capacity.sku.name
