[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $RuntimeFile,
    [Parameter(Mandatory = $true)] [ValidatePattern('^sha256:[a-f0-9]{64}$')] [string] $ImageDigest,
    [switch] $RenderOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$runtime = Get-Content -LiteralPath $RuntimeFile -Raw | ConvertFrom-Json
if ($runtime.deployment_profile -ne 'gold-hapi-fhir-r4') {
    throw 'Expected the fhir_runtime Terraform output, not a legacy workspace deployment.'
}
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\kubernetes\hapi-fhir.yaml') -Raw
$replacements = @{
    IMAGE = "$($runtime.acr_login_server)/hapi-fhir@$ImageDigest"
    FHIR_IDENTITY_CLIENT_ID = $runtime.fhir_identity_client_id
    TENANT_ID = $runtime.tenant_id
    GOLD_QUERY_URI = $runtime.gold_query_uri
    GOLD_DATABASE_NAME = $runtime.gold_database_name
    FHIR_BASE_URL = $runtime.fhir_base_url
    AKS_SUBNET_NAME = $runtime.aks_subnet_name
    FHIR_PRIVATE_IP = $runtime.fhir_private_ip
    APIM_SUBNET_CIDR = $runtime.apim_subnet_cidr
}
foreach ($entry in $replacements.GetEnumerator()) {
    if ([string]::IsNullOrWhiteSpace([string] $entry.Value)) {
        throw "Missing deployment value: $($entry.Key)."
    }
    $manifest = $manifest.Replace('"' + "__$($entry.Key)__" + '"', (ConvertTo-Json -InputObject ([string] $entry.Value) -Compress))
}
if ($manifest -match '__[A-Z_]+__') {
    throw 'An unresolved Kubernetes template value remains.'
}
if ($RenderOnly) {
    $manifest
    return
}

foreach ($tool in @('az', 'kubectl', 'kubelogin')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "Required deployment tool '$tool' is not installed."
    }
}
$kubeconfig = Join-Path ([IO.Path]::GetTempPath()) ("hie-fhir-" + [guid]::NewGuid().ToString() + '.kubeconfig')
try {
    & az aks get-credentials --subscription $runtime.subscription_id --resource-group $runtime.resource_group_name `
        --name $runtime.aks_cluster_name --file $kubeconfig --format exec --overwrite-existing --only-show-errors
    if ($LASTEXITCODE -ne 0) { throw 'Failed to obtain this AKS cluster context. Verify the tenant, RBAC and private connectivity.' }
    & kubelogin convert-kubeconfig --kubeconfig $kubeconfig -l azurecli --tenant-id $runtime.tenant_id
    if ($LASTEXITCODE -ne 0) { throw 'Failed to configure Azure CLI authentication for the private cluster.' }
    $manifest | & kubectl --kubeconfig $kubeconfig apply --server-side --field-manager=hie-fhir -f -
    if ($LASTEXITCODE -ne 0) { throw 'Kubernetes deployment failed; no rollout success is assumed.' }
    & kubectl --kubeconfig $kubeconfig -n fhir rollout status deployment/hapi-fhir --timeout=600s
    if ($LASTEXITCODE -ne 0) { throw 'HAPI is not ready. Check image pull, workload identity and the Gold database viewer grant.' }
    & kubectl --kubeconfig $kubeconfig -n fhir wait service/hapi-fhir `
        "--for=jsonpath={.status.loadBalancer.ingress[0].ip}=$($runtime.fhir_private_ip)" --timeout=300s
    if ($LASTEXITCODE -ne 0) { throw 'The internal load balancer did not acquire the private address configured in APIM.' }
    Write-Host "HAPI rollout is ready at $($runtime.fhir_base_url). Run the authenticated ePNA smoke test next."
}
finally {
    if (Test-Path -LiteralPath $kubeconfig) {
        Remove-Item -LiteralPath $kubeconfig -Force
    }
}
