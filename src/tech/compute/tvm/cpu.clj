(ns tvm-clj.compute.cpu
  (:require [tvm-clj.tvm-bindings :as bindings]
            [tvm-clj.base :as tvm-base]
            [tvm-clj.api :as api]
            [tech.compute.driver :as drv]
            [tech.compute.tvm.registry :as tvm-reg]
            [tech.compute.tvm.driver :as tvm-driver]
            [tech.compute.tvm.device-buffer :as dbuf]
            [tech.compute.tvm.shared :as tvm-shared]
            [tech.compute.cpu.driver :as cpu-driver]
            [tech.resource :as resource]
            [tech.datatype.core :as dtype]))


(declare driver)

(defrecord CPUStream [device-fn stream]
  drv/PStream
  (copy-host->device [_ host-buffer host-offset
                      device-buffer device-offset elem-count]
    (cpu-driver/with-stream-dispatch stream
      (tvm-shared/copy-device->device host-buffer host-offset
                                      device-buffer device-offset elem-count nil)))
  (copy-device->host [_ device-buffer device-offset
                      host-buffer host-offset elem-count]
    (cpu-driver/with-stream-dispatch stream
      (tvm-shared/copy-device->device device-buffer device-offset
                                      host-buffer host-offset elem-count nil)))
  (copy-device->device [_ dev-a dev-a-off dev-b dev-b-off elem-count]
    (cpu-driver/with-stream-dispatch stream
      (tvm-shared/copy-device->device dev-a dev-a-off
                                      dev-b dev-b-off elem-count nil)))
  (sync-with-host [_]
    (drv/sync-with-host stream))
  (sync-with-stream [_ dst-stream]
    (drv/sync-with-stream stream (:stream dst-stream)))

  resource/PResource
  (release-resource [_] )

  tvm-driver/PTVMStream
  (call-function [_ fn arg-list]
    (cpu-driver/with-stream-dispatch stream
      (apply fn arg-list)))

  drv/PDriverProvider
  (get-driver [_] (drv/get-driver (device-fn)))

  drv/PDeviceProvider
  (get-device [_] (device-fn))

  tvm-reg/PDeviceInfo
  (device-type [this] (tvm-reg/device-type (device-fn)))

  tvm-reg/PDriverInfo
  (device-id [this] (tvm-reg/device-id (device-fn))))

(defn is-main-thread-cpu-stream?
  [^CPUStream stream]
  (cpu-driver/is-main-thread-cpu-stream? (.stream stream)))

(defmacro with-stream-dispatch
  [stream & body]
  `(cpu-driver/with-stream-dispatch (.stream stream)
     ~@body))

(defrecord CPUDevice [error-atom default-stream]
  tvm-reg/PDeviceInfo
  (device-id [this] 0)

  tvm-reg/PDriverInfo
  (device-type [_] (tvm-reg/cpu-device-type))

  drv/PDevice
  (memory-info-impl [device]
    {:free 0xFFFFFFFF
     :total 0xFFFFFFF})
  (create-stream-impl [device]
    (->CPUStream device (cpu-driver/cpu-stream device error-atom)))
  (allocate-device-buffer-impl [device elem-count elem-type]
    (dbuf/make-cpu-device-buffer elem-type elem-count))
  (allocate-rand-buffer-impl [device elem-count]
    (dbuf/make-cpu-device-buffer :float32 elem-count))
  (supports-create-stream? [device] true)
  (default-stream [device] @default-stream)

  drv/PDriverProvider
  (get-driver [dev] (driver))

  drv/PDeviceProvider
  (get-device [dev] dev)

  resource/PResource
  (release-resource [dev]))


(def cpu-devices
  (memoize
   (fn []
     (let [device (->CPUDevice (atom nil) (atom nil))
           default-stream (->CPUStream (constantly device)
                                       (cpu-driver/main-thread-cpu-stream))]
       (swap! (:default-stream device) (constantly default-stream))
       [device]))))

(defrecord CPUDriver []
  drv/PDriver
  (get-devices [driver]
    (cpu-devices))
  (allocate-host-buffer-impl [driver elem-count elem-type options]
    (dbuf/make-cpu-device-buffer elem-type elem-count))

  tvm-reg/PDeviceIdToDevice
  (device-id->device [driver dev-id]
    (when-not (= 0 dev-id)
      (throw (ex-info "CPU device types only have device id 0" {})))
    (first (drv/get-devices driver)))

  tvm-reg/PDriverInfo
  (device-type [_] (tvm-reg/cpu-device-type))

  tvm-reg/PCompileModule
  (gpu-scheduling? [driver] false)
  (device-datatypes? [driver] false)
  (schedule-injective! [driver stage compute-op options]
    (apply api/stage-cpu-injective stage compute-op options))

  (->module-impl [driver sched-data-seq options]
    (api/schedules->fns sched-data-seq
                        :build-config (:build-config options)
                        :target-host (:target-host options)
                        :target-name :llvm)))

(def driver
  (memoize
   (fn [] (->CPUDriver))))

(tvm-reg/add-device-type (bindings/device-type->device-type-int :cpu) (driver))

(defn ptr->device-buffer
  [ptr & {:keys [dtype]}]
  (let [dtype (or dtype (dtype/get-datatype ptr))
        shape [(dtype/ecount ptr)]
        device (first (cpu-devices))
        device-type (tvm-reg/device-type device)
        device-id (tvm-reg/device-id device)]
    (tvm-base/pointer->tvm-ary ptr device-type device-id dtype shape nil 0)))
