{{/*
Chart name, truncated/sanitised for use in resource names.
*/}}
{{- define "agentplane.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully qualified app name: <release>-<chart> unless the release name already contains the chart
name, or fullnameOverride is set.
*/}}
{{- define "agentplane.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "agentplane.controlPlane.fullname" -}}
{{- printf "%s-control-plane" (include "agentplane.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "agentplane.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Common labels, applied to every resource this chart renders.
*/}}
{{- define "agentplane.labels" -}}
helm.sh/chart: {{ include "agentplane.chart" . }}
{{ include "agentplane.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "agentplane.selectorLabels" -}}
app.kubernetes.io/name: {{ include "agentplane.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "agentplane.controlPlane.selectorLabels" -}}
{{ include "agentplane.selectorLabels" . }}
app.kubernetes.io/component: control-plane
{{- end -}}

{{- define "agentplane.controlPlane.labels" -}}
{{ include "agentplane.labels" . }}
app.kubernetes.io/component: control-plane
{{- end -}}

{{- define "agentplane.serviceAccountName" -}}
{{- if .Values.controlPlane.serviceAccount.create -}}
{{- default (include "agentplane.controlPlane.fullname" .) .Values.controlPlane.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.controlPlane.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/*
The control plane's own cluster-local DNS name, as a Certificate SAN and as the value workers
would use for AGENTPLANE_CONTROL_PLANE_URL.
*/}}
{{- define "agentplane.controlPlane.serviceDnsName" -}}
{{- printf "%s.%s.svc.cluster.local" (include "agentplane.controlPlane.fullname" .) .Release.Namespace -}}
{{- end -}}
