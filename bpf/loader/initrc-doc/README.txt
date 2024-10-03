This directory contains comment stripped versions of
  //system/bpf/bpfloader/bpfloader.rc
or
  //packages/modules/Connectivity/bpf/loader/netbpfload.rc
(as appropriate) from previous versions of Android.

Generated via:
  (cd ../../../../../../system/bpf && git cat-file -p remotes/aosp/android11-release:bpfloader/bpfloader.rc; ) | egrep -v '^ *#' > bpfloader-sdk30-11-R.rc
  (cd ../../../../../../system/bpf && git cat-file -p remotes/aosp/android12-release:bpfloader/bpfloader.rc; ) | egrep -v '^ *#' > bpfloader-sdk31-12-S.rc
  (cd ../../../../../../system/bpf && git cat-file -p remotes/aosp/android13-release:bpfloader/bpfloader.rc; ) | egrep -v '^ *#' > bpfloader-sdk33-13-T.rc
  (cd ../../../../../../system/bpf && git cat-file -p remotes/aosp/android14-release:bpfloader/bpfloader.rc; ) | egrep -v '^ *#' > bpfloader-sdk34-14-U.rc
  git cat-file -p remotes/aosp/android14-qpr2-release:netbpfload/netbpfload.rc | egrep -v '^ *#' > bpfloader-sdk34-14-U-QPR2-24Q1.rc
  git cat-file -p remotes/aosp/android14-qpr3-release:netbpfload/netbpfload.rc | egrep -v '^ *#' > bpfloader-sdk34-14-U-QPR3-24Q2.rc
  git cat-file -p remotes/aosp/android15-release:netbpfload/netbpfload.rc      | egrep -v '^ *#' > bpfloader-sdk35-15-V-24Q3.rc
  git cat-file -p remotes/aosp/main:bpf/loader/netbpfload.rc                   | egrep -v '^ *#' > bpfloader-sdk35-15-V-QPR1-24Q4.rc

see also:
  https://android.googlesource.com/platform/system/bpf/+/refs/heads/android11-release/bpfloader/bpfloader.rc
  https://android.googlesource.com/platform/system/bpf/+/refs/heads/android12-release/bpfloader/bpfloader.rc
  https://android.googlesource.com/platform/system/bpf/+/refs/heads/android13-release/bpfloader/bpfloader.rc
  https://android.googlesource.com/platform/system/bpf/+/refs/heads/android14-release/bpfloader/bpfloader.rc
  https://android.googlesource.com/platform/system/bpf/+/refs/heads/android14-qpr1-release/bpfloader/bpfloader.rc
  https://android.googlesource.com/platform/system/bpf/+/refs/heads/android14-qpr2-release/bpfloader/ (rc file is gone in QPR2)
  https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android14-qpr2-release/netbpfload/netbpfload.rc
  https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android14-qpr3-release/netbpfload/netbpfload.rc
  https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android15-release/netbpfload/netbpfload.rc
  https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android15-qpr1-release/netbpfload/netbpfload.rc
  https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/netbpfload/netbpfload.rc
or:
  https://googleplex-android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/24Q1-release/netbpfload/netbpfload.rc
  https://googleplex-android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/24Q2-release/netbpfload/netbpfload.rc
  https://googleplex-android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/24Q3-release/netbpfload/netbpfload.rc
  https://googleplex-android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/24Q4-release/bpf/loader/netbpfload.rc

this is entirely equivalent to:
  (cd /android1/system/bpf && git cat-file -p remotes/goog/rvc-dev:bpfloader/bpfloader.rc; ) | egrep -v '^ *#' > bpfloader-sdk30-11-R.rc
  (cd /android1/system/bpf && git cat-file -p remotes/goog/sc-dev:bpfloader/bpfloader.rc;  ) | egrep -v '^ *#' > bpfloader-sdk31-12-S.rc
  (cd /android1/system/bpf && git cat-file -p remotes/goog/tm-dev:bpfloader/bpfloader.rc;  ) | egrep -v '^ *#' > bpfloader-sdk33-13-T.rc
  (cd /android1/system/bpf && git cat-file -p remotes/goog/udc-dev:bpfloader/bpfloader.rc; ) | egrep -v '^ *#' > bpfloader-sdk34-14-U.rc

it is also equivalent to:
  (cd /android1/system/bpf && git cat-file -p remotes/goog/rvc-qpr-dev:bpfloader/bpfloader.rc; ) | egrep -v '^ *#' > bpfloader-sdk30-11-R.rc
  (cd /android1/system/bpf && git cat-file -p remotes/goog/sc-v2-dev:bpfloader/bpfloader.rc;   ) | egrep -v '^ *#' > bpfloader-sdk31-12-S.rc
  (cd /android1/system/bpf && git cat-file -p remotes/goog/tm-qpr-dev:bpfloader/bpfloader.rc;  ) | egrep -v '^ *#' > bpfloader-sdk33-13-T.rc
  (cd /android1/system/bpf && git cat-file -p remotes/goog/udc-qpr-dev:bpfloader/bpfloader.rc; ) | egrep -v '^ *#' > bpfloader-sdk34-14-U.rc

ie. there were no changes between R/S/T and R/S/T QPR3, and no change between U and U QPR1.

Note: Sv2 sdk/api level is actually 32, it just didn't change anything wrt. bpf, so doesn't matter.


Key takeaways:

= R bpfloader (platform)
  - CHOWN + SYS_ADMIN
  - asynchronous startup
  - platform only
  - proc file setup handled by initrc

= S bpfloader (platform)
  - adds NET_ADMIN
  - synchronous startup
  - platform + mainline tethering offload

= T bpfloader (platform)
  - platform + mainline networking (including tethering offload)
  - supported btf for maps via exec of btfloader

= U bpfloader (platform)
  - proc file setup moved into bpfloader binary
  - explicitly specified user and groups:
    group root graphics network_stack net_admin net_bw_acct net_bw_stats net_raw system
    user root

= U QPR2 [24Q1] bpfloader (platform netbpfload -> platform bpfloader)
  - drops support of btf for maps
  - invocation of /system/bin/netbpfload binary, which after handling *all*
    networking bpf related things executes the platform /system/bin/bpfloader
    which handles non-networking bpf.
  - Note: this does not (by itself) call into apex NetBpfLoad

= U QPR3 [24Q2] bpfloader (platform netbpfload -> apex netbpfload -> platform bpfloader)
  - platform NetBpfload *always* execs into apex NetBpfLoad,
  - shipped with mainline tethering apex that includes NetBpfLoad binary.

= V [24Q3] bpfloader (apex netbpfload -> platform bpfloader)
  - no significant changes, though it does hard require the apex NetBpfLoad
    by virtue of the platform NetBpfLoad no longer being present.
    ie. the apex must override the platform 'bpfloader' service for 35+:
    the V FRC M-2024-08+ tethering apex does this.

= V QPR1 [24Q4] bpfloader (apex netbpfload -> platform bpfloader)
  - made netd start earlier (previously happened in parallel to zygote)
  - renamed and moved the trigger out of netbpload.rc into
    //system/core/rootdir/init.rc
  - the new sequence is:
      trigger post-fs-data        (logd available, starts apexd)
      trigger load-bpf-programs   (does: exec_start bpfloader)
      trigger bpf-progs-loaded    (does: start netd)
      trigger zygote-start
  - this is more or less irrelevant from the point of view of the bpfloader,
    but it does mean netd init could fail and abort the boot earlier,
    before 'A/B update_verifier marks a successful boot'.
    Though note that due to netd being started asynchronously, it is racy.

Note that there is now a copy of 'netbpfload' provided by the tethering apex
mainline module at /apex/com.android.tethering/bin/netbpfload, which due
to the use of execve("/system/bin/bpfloader") relies on T+ selinux which was
added for btf map support (specifically the ability to exec the "btfloader").

= mainline tethering apex M-2024-08+ overrides the platform service for V+
  thus loading mainline (ie. networking) bpf programs from mainline 'NetBpfLoad'
  and platform ones from platform 'bpfloader'.

= mainline tethering apex M-2024-09+ changes T+ behaviour (U QPR3+ unaffected)
  netd -> netd_updatable.so -> ctl.start=mdnsd_netbpfload -> load net bpf programs
