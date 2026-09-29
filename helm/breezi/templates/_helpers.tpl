{{/*
The labels every resource carries besides its own app.kubernetes.io/name.
*/}}
{{- define "breezi.commonLabels" -}}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/part-of: breezi
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end }}

{{/*
The probes of a service: its health server answers GET /healthz while the process runs and
GET /readyz once the service has started (see CLAUDE.md, "Running in containers").
The container port named "health" is HEALTH_PORT from the ConfigMap.
*/}}
{{- define "breezi.healthProbes" -}}
livenessProbe:
  httpGet:
    path: /healthz
    port: health
  periodSeconds: 10
readinessProbe:
  httpGet:
    path: /readyz
    port: health
  periodSeconds: 5
{{- end }}

{{/*
Pod annotations that change whenever the ConfigMap or the Secret changes, so a helm upgrade
that only changes configuration still rolls the pods (a pod reads its environment once, at start).
*/}}
{{- define "breezi.checksumAnnotations" -}}
checksum/config: {{ include (print $.Template.BasePath "/configmap.yaml") . | sha256sum }}
checksum/secrets: {{ include (print $.Template.BasePath "/secret.yaml") . | sha256sum }}
{{- end }}
