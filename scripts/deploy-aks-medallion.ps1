[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [string]$ResourceGroup,

  [Parameter(Mandatory = $true)]
  [string]$BicepParameters,

  [string]$ImageTag = "0.1.0"
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = Split-Path $PSScriptRoot -Parent
$bicepFile = Join-Path $repoRoot 'infra\aks-medallion\main.bicep'
$manifest = Join-Path $repoRoot 'kubernetes\aks-medallion.yaml'
$servicePath = Join-Path $repoRoot 'services\aks-medallion'
$deploymentName = 'aks-medallion'

az deployment group create `
  --name $deploymentName `
  --resource-group $ResourceGroup `
  --template-file $bicepFile `
  --parameters "@$BicepParameters" | Out-Null

$outputJson = az deployment group show `
  --resource-group $ResourceGroup `
  --name $deploymentName `
  --query properties.outputs `
  --output json
$outputs = $outputJson | ConvertFrom-Json

$acrServer = $outputs.acrLoginServer.value
$image = "$acrServer/aks-medallion:$ImageTag"
az acr build --registry ($acrServer.Split('.')[0]) --image "aks-medallion:$ImageTag" $servicePath

az aks get-credentials `
  --resource-group $ResourceGroup `
  --name $outputs.aksName.value `
  --overwrite-existing

$rendered = Get-Content -Raw $manifest
$replacements = @{
  'REPLACE_WITH_WORKLOAD_IDENTITY_CLIENT_ID' = $outputs.workloadIdentityClientId.value
  'REPLACE_WITH_EVENT_HUBS_NAMESPACE' = $outputs.eventHubsNamespace.value
  'REPLACE_WITH_SERVICE_BUS_NAMESPACE' = $outputs.serviceBusNamespace.value
  'REPLACE_WITH_STORAGE_ACCOUNT_URL' = $outputs.storageAccountUrl.value
  'REPLACE_WITH_ADX_INGESTION_URL' = $outputs.adxIngestionUrl.value
  'REPLACE_WITH_SERVICE_BUS_RESOURCE_NAME' = $outputs.serviceBusNamespace.value.Split('.')[0]
  'REPLACE_WITH_IMAGE' = $image
}
foreach ($token in $replacements.Keys) {
  $rendered = $rendered.Replace($token, $replacements[$token])
}

$temporaryManifest = Join-Path ([System.IO.Path]::GetTempPath()) "hie-aks-medallion-$PID.yaml"
try {
  [System.IO.File]::WriteAllText($temporaryManifest, $rendered)
  kubectl apply -f $temporaryManifest
  kubectl -n hie-medallion rollout status deployment/medallion-bronze --timeout=5m
  kubectl -n hie-medallion rollout status deployment/medallion-silver --timeout=5m
  kubectl -n hie-medallion rollout status deployment/medallion-gold --timeout=5m
} finally {
  Remove-Item -LiteralPath $temporaryManifest -ErrorAction SilentlyContinue
}
