#!/bin/bash
# Signs a Jarvis release with its key, which only root can read (scripts/install-phone.sh).
/usr/local/lib/dibs-root/sign-apk "$DIBS_ROOT_FILES/app.apk" "$DIBS_ROOT_OUT/app.apk"
