$ErrorActionPreference = 'Stop'
$fabricApi = 'https://api.fabric.microsoft.com/v1'
$FabricCapacityId = $env:FABRIC_CAPACITY_ID
$WorkspaceDisplayName = $env:WORKSPACE_DISPLAY_NAME
$EventhouseDisplayName = $env:EVENTHOUSE_DISPLAY_NAME
$KqlDatabaseDisplayName = $env:KQL_DATABASE_DISPLAY_NAME
$ActivatorDisplayName = $env:ACTIVATOR_DISPLAY_NAME
$AlertRecipient = $env:ALERT_RECIPIENT
$EnableActivatorRule = $env:ENABLE_ACTIVATOR_RULE -eq 'true'

$requiredEnvironmentVariables = @(
    'FABRIC_CAPACITY_ID',
    'WORKSPACE_DISPLAY_NAME',
    'EVENTHOUSE_DISPLAY_NAME',
    'KQL_DATABASE_DISPLAY_NAME',
    'ACTIVATOR_DISPLAY_NAME',
    'ALERT_RECIPIENT',
    'KQL_DATABASE_SCHEMA_BASE64',
    'REFLEX_ENTITIES_TEMPLATE_BASE64'
)
foreach ($variableName in $requiredEnvironmentVariables) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($variableName))) {
        throw "Required environment variable '$variableName' is missing."
    }
}

function ConvertFrom-SecureToken {
    param([Parameter(Mandatory = $true)] $Token)

    if ($Token -isnot [System.Security.SecureString]) {
        return [string] $Token
    }

    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Token)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

$accessToken = Get-AzAccessToken -ResourceUrl 'https://api.fabric.microsoft.com'
$headers = @{
    Authorization = "Bearer $(ConvertFrom-SecureToken -Token $accessToken.Token)"
    'Content-Type' = 'application/json'
}

function Invoke-FabricRequest {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet('GET', 'POST')]
        [string] $Method,

        [Parameter(Mandatory = $true)]
        [string] $Uri,

        [object] $Body
    )

    $parameters = @{
        Method = $Method
        Uri = $Uri
        Headers = $headers
        UseBasicParsing = $true
    }

    if ($null -ne $Body) {
        $parameters.Body = $Body | ConvertTo-Json -Depth 100 -Compress
    }

    $response = Invoke-WebRequest @parameters
    $content = if ([string]::IsNullOrWhiteSpace($response.Content)) {
        $null
    }
    else {
        $response.Content | ConvertFrom-Json
    }

    return @{
        StatusCode = [int] $response.StatusCode
        Headers = $response.Headers
        Content = $content
    }
}

function Wait-FabricOperation {
    param([Parameter(Mandatory = $true)] [string] $OperationUri)

    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        Start-Sleep -Seconds 10
        $operation = Invoke-FabricRequest -Method GET -Uri $OperationUri
        if ($operation.Content.status -eq 'Succeeded') {
            return
        }
        if ($operation.Content.status -in @('Failed', 'Cancelled')) {
            throw "Fabric operation failed: $($operation.Content | ConvertTo-Json -Depth 20 -Compress)"
        }
    }

    throw "Timed out waiting for Fabric operation $OperationUri."
}

function Complete-FabricResponse {
    param([Parameter(Mandatory = $true)] [hashtable] $Response)

    if ($Response.StatusCode -eq 202) {
        $operationUri = [string] $Response.Headers.Location
        if ([string]::IsNullOrWhiteSpace($operationUri)) {
            throw 'Fabric returned 202 without a Location header.'
        }
        Wait-FabricOperation -OperationUri $operationUri
    }
}

function Get-WorkspaceByName {
    $response = Invoke-FabricRequest -Method GET -Uri "$fabricApi/workspaces"
    return @($response.Content.value) | Where-Object displayName -eq $WorkspaceDisplayName | Select-Object -First 1
}

function Wait-WorkspaceByName {
    for ($attempt = 0; $attempt -lt 12; $attempt++) {
        $workspace = Get-WorkspaceByName
        if ($null -ne $workspace) {
            return $workspace
        }
        Start-Sleep -Seconds 5
    }

    return $null
}

function Get-ItemByName {
    param(
        [Parameter(Mandatory = $true)] [string] $WorkspaceId,
        [Parameter(Mandatory = $true)] [string] $DisplayName,
        [Parameter(Mandatory = $true)] [string] $Type
    )

    $response = Invoke-FabricRequest -Method GET -Uri "$fabricApi/workspaces/$WorkspaceId/items"
    return @($response.Content.value) |
        Where-Object { $_.displayName -eq $DisplayName -and $_.type -eq $Type } |
        Select-Object -First 1
}

function Wait-ItemByName {
    param(
        [Parameter(Mandatory = $true)] [string] $WorkspaceId,
        [Parameter(Mandatory = $true)] [string] $DisplayName,
        [Parameter(Mandatory = $true)] [string] $Type
    )

    for ($attempt = 0; $attempt -lt 12; $attempt++) {
        $item = Get-ItemByName -WorkspaceId $WorkspaceId -DisplayName $DisplayName -Type $Type
        if ($null -ne $item) {
            return $item
        }
        Start-Sleep -Seconds 5
    }

    return $null
}

function Set-ItemDefinition {
    param(
        [Parameter(Mandatory = $true)] [string] $WorkspaceId,
        [Parameter(Mandatory = $true)] [string] $ItemId,
        [Parameter(Mandatory = $true)] [object] $Definition
    )

    $response = Invoke-FabricRequest `
        -Method POST `
        -Uri "$fabricApi/workspaces/$WorkspaceId/items/$ItemId/updateDefinition" `
        -Body @{ definition = $Definition }
    Complete-FabricResponse -Response $response
}

$workspace = Wait-WorkspaceByName
if ($null -eq $workspace) {
    $response = Invoke-FabricRequest -Method POST -Uri "$fabricApi/workspaces" -Body @{
        displayName = $WorkspaceDisplayName
        description = 'Gold operational serving workspace for the HIE ePNA proof of concept.'
        capacityId = $FabricCapacityId
    }
    Complete-FabricResponse -Response $response
    $workspace = Get-WorkspaceByName
}
if ($null -eq $workspace) {
    throw "Workspace '$WorkspaceDisplayName' was not found after creation."
}

$workspaceDetails = Invoke-FabricRequest -Method GET -Uri "$fabricApi/workspaces/$($workspace.id)"
if ($workspaceDetails.Content.capacityId -ne $FabricCapacityId) {
    throw "Workspace '$WorkspaceDisplayName' is not assigned to the requested Fabric capacity '$FabricCapacityId'. Explicitly reassign it in Fabric or choose a new workspace name; this deployment does not migrate workspaces."
}

$eventhouse = Wait-ItemByName -WorkspaceId $workspace.id -DisplayName $EventhouseDisplayName -Type 'Eventhouse'
if ($null -eq $eventhouse) {
    $response = Invoke-FabricRequest -Method POST -Uri "$fabricApi/workspaces/$($workspace.id)/eventhouses" -Body @{
        displayName = $EventhouseDisplayName
        description = 'Gold Eventhouse for governed real-time HIE clinical events.'
    }
    Complete-FabricResponse -Response $response
    $eventhouse = Get-ItemByName -WorkspaceId $workspace.id -DisplayName $EventhouseDisplayName -Type 'Eventhouse'
}
if ($null -eq $eventhouse) {
    throw "Eventhouse '$EventhouseDisplayName' was not found after creation."
}

$databaseProperties = @{
    databaseType = 'ReadWrite'
    parentEventhouseItemId = $eventhouse.id
    oneLakeCachingPeriod = 'P30D'
    oneLakeStandardStoragePeriod = 'P365D'
}
$databaseDefinition = @{
    parts = @(
        @{
            path = 'DatabaseProperties.json'
            payload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($databaseProperties | ConvertTo-Json -Depth 10 -Compress)))
            payloadType = 'InlineBase64'
        },
        @{
            path = 'DatabaseSchema.kql'
            payload = $env:KQL_DATABASE_SCHEMA_BASE64
            payloadType = 'InlineBase64'
        }
    )
}

$kqlDatabase = Wait-ItemByName -WorkspaceId $workspace.id -DisplayName $KqlDatabaseDisplayName -Type 'KQLDatabase'
if ($null -eq $kqlDatabase) {
    $response = Invoke-FabricRequest -Method POST -Uri "$fabricApi/workspaces/$($workspace.id)/kqlDatabases" -Body @{
        displayName = $KqlDatabaseDisplayName
        description = 'FHIR R4 and USCDI+ aligned Gold serving model for ePNA.'
        definition = $databaseDefinition
    }
    Complete-FabricResponse -Response $response
    $kqlDatabase = Get-ItemByName -WorkspaceId $workspace.id -DisplayName $KqlDatabaseDisplayName -Type 'KQLDatabase'
}
else {
    Set-ItemDefinition -WorkspaceId $workspace.id -ItemId $kqlDatabase.id -Definition $databaseDefinition
}
if ($null -eq $kqlDatabase) {
    throw "KQL database '$KqlDatabaseDisplayName' was not found after creation."
}

$reflexJson = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($env:REFLEX_ENTITIES_TEMPLATE_BASE64))
$reflexJson = $reflexJson.Replace('__EVENTHOUSE_ITEM_ID__', [string] $eventhouse.id)
$reflexJson = $reflexJson.Replace('__ALERT_RECIPIENT__', $AlertRecipient.Replace('\', '\\').Replace('"', '\"'))
$reflexJson = $reflexJson.Replace('__RULE_ENABLED__', $EnableActivatorRule.ToString().ToLowerInvariant())
[void] ($reflexJson | ConvertFrom-Json -Depth 100)
$reflexDefinition = @{
    format = 'json'
    parts = @(
        @{
            path = 'ReflexEntities.json'
            payload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($reflexJson))
            payloadType = 'InlineBase64'
        }
    )
}

$activator = Wait-ItemByName -WorkspaceId $workspace.id -DisplayName $ActivatorDisplayName -Type 'Reflex'
if ($null -eq $activator) {
    $response = Invoke-FabricRequest -Method POST -Uri "$fabricApi/workspaces/$($workspace.id)/items" -Body @{
        displayName = $ActivatorDisplayName
        type = 'Reflex'
        description = 'POC alert for clinician-approved ePNA qualification signals on active ED encounters.'
        definition = $reflexDefinition
    }
    Complete-FabricResponse -Response $response
    $activator = Get-ItemByName -WorkspaceId $workspace.id -DisplayName $ActivatorDisplayName -Type 'Reflex'
}
else {
    Set-ItemDefinition -WorkspaceId $workspace.id -ItemId $activator.id -Definition $reflexDefinition
}
if ($null -eq $activator) {
    throw "Activator '$ActivatorDisplayName' was not found after creation."
}

$DeploymentScriptOutputs = @{
    workspaceId = [string] $workspace.id
    eventhouseId = [string] $eventhouse.id
    kqlDatabaseId = [string] $kqlDatabase.id
    activatorId = [string] $activator.id
}
