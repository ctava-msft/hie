using './capacity.bicep'

param capacityName = 'hiegoldf2poc'
param location = 'westcentralus'
param capacitySku = 'F2'
param capacityAdministratorUpns = [
  readEnvironmentVariable('FABRIC_CAPACITY_ADMIN_UPN')
]
