// Not yet in AB Code
MESSAGE "".

&ANALYZE-SUSPEND _VERSION-NUMBER AB_v10r12 GUI
&ANALYZE-RESUME

&SCOPED-DEFINE WINDOW-NAME wSettings
// Read-only
MESSAGE "".

&ANALYZE-SUSPEND _UIB-CODE-BLOCK _CUSTOM _DEFINITIONS wSettings 
// Editable
MESSAGE "".
&ANALYZE-RESUME

&ANALYZE-SUSPEND _UIB-PREPROCESSOR-BLOCK
// Read-only
MESSAGE "".
&ANALYZE-RESUME

&ANALYZE-SUSPEND _UIB-CODE-BLOCK _CUSTOM _MAIN-BLOCK wSettings  
// Read-only
MESSAGE "".
&ANALYZE-RESUME

&ANALYZE-SUSPEND _UIB-CODE-BLOCK _CONTROL wSettings wSettings
// Editable
MESSAGE "".
&ANALYZE-RESUME

&ANALYZE-SUSPEND _UIB-CODE-BLOCK _PROCEDURE disable_UI wSettings  _DEFAULT-DISABLE
// Read-only
MESSAGE "".
&ANALYZE-RESUME

&ANALYZE-SUSPEND _UIB-CODE-BLOCK _PROCEDURE initializeObject wSettings 
// Editable
MESSAGE "".
&ANALYZE-RESUME

&ANALYZE-SUSPEND _UIB-CODE-BLOCK _PROCEDURE xxx wSettings  _FREEFORM
// Editable
MESSAGE "".
&ANALYZE-RESUME

&ANALYZE-SUSPEND _UIB-CODE-BLOCK _FUNCTION yyy getCurrent wSettings
// Editable 
MESSAGE "".
&ANALYZE-RESUME
