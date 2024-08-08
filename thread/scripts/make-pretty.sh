#!/usr/bin/env bash

ANDROID_ROOT_DIR=$(
    while [ ! -d ".repo" ] && [ "$PWD" != "/" ]; do cd ..; done
    pwd
)

if [ ! -d "$ANDROID_ROOT_DIR/.repo" ]; then
    echo "Error: The script has to run in an Android repo checkout"
    exit 1
fi

GOOGLE_JAVA_FORMAT=$ANDROID_ROOT_DIR/prebuilts/tools/common/google-java-format/google-java-format
ANDROID_BP_FORMAT=$ANDROID_ROOT_DIR/prebuilts/build-tools/linux-x86/bin/bpfmt
AIDL_FORMAT=$ANDROID_ROOT_DIR/system/tools/aidl/aidl-format.sh

CONNECTIVITY_DIR=$ANDROID_ROOT_DIR/packages/modules/Connectivity
OPENTHREAD_DIR=$ANDROID_ROOT_DIR/external/openthread
OTBR_POSIX_DIR=$ANDROID_ROOT_DIR/external/ot-br-posix

ALLOWED_CODE_DIRS=($CONNECTIVITY_DIR $OPENTHREAD_DIR $OTBR_POSIX_DIR)
CODE_DIR=$(git rev-parse --show-toplevel)

if [[ ! " ${ALLOWED_CODE_DIRS[@]} " =~ " ${CODE_DIR} " ]]; then
    echo "Error: The script has to run in the Git project Connectivity, openthread or ot-br-posix"
    exit 1
fi

if [[ $CODE_DIR == $CONNECTIVITY_DIR ]]; then
    CODE_DIR=$CODE_DIR"/thread"
fi

$GOOGLE_JAVA_FORMAT --aosp -i $(find $CODE_DIR -name "*.java")
$ANDROID_BP_FORMAT -w $(find $CODE_DIR -name "*.bp")
$AIDL_FORMAT -w $(find $CODE_DIR -name "*.aidl")
