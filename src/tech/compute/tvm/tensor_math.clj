(ns tvm-clj.compute.tensor-math
  (:require [tech.compute.tensor :as ct]
            [tech.compute.tensor.dimensions :as ct-dims]
            [clojure.string :as s]
            [tvm-clj.api :as api]
            [tvm-clj.tvm-bindings :as bindings]
            [tvm-clj.base :as base]
            [tvm-clj.compute.registry :as tvm-reg]
            [tvm-clj.compute.device-buffer :as dbuf]
            [tech.resource :as resource]
            [tech.compute.driver :as drv]
            [tvm-clj.compute.cpu :as cpu]
            [tvm-clj.compute.gpu]
            [tech.compute.tensor.math :as tm]
            [tech.datatype.core :as dtype]
            [clojure.core.matrix :as m])
  (:import [tvm_clj.compute.cpu CPUStream]
           [tvm_clj.compute.gpu GPUStream]))


(defonce ^:dynamic *fn-map* (atom {}))

(defrecord PRemoveFunction [device-type device-id fn-name]
  resource/PResource
  (release-resource [_] (swap! *fn-map* dissoc [device-type device-id fn-name])))


(defn y-dim-tvm-fn
  [n-dims detailed-fn]
  ;;The compute op turns the function argiments into iteration variables
  (with-meta (fn [& index-args]
               (detailed-fn index-args))
    {:arglists (->> (range n-dims)
                    (mapv #(symbol (str "i" %))))}))



(defn tensor-result-arg->mangle-str
  [tensor]
  (str (name (ct/get-datatype tensor))
       "_"
       (count (ct/shape tensor))
       "_"
       (if (ct/dense? tensor)
         "dense"
         "sparse")
       "_"
       (if (dbuf/has-byte-offset? tensor)
         "offset"
         "nooffset")))


(defn tensor-read-arg->mangle-str
  "For arguments where we ourselves are interpreting the argument."
  [tensor]
  (str (name (ct/get-datatype tensor))
       "_"
       (if (dbuf/has-byte-offset? tensor)
         "offset"
         "nooffset")))


(defn get-fn-name
  [stem dest & args]
  (s/join "_" (concat [stem (tensor-result-arg->mangle-str dest)]
                      (map tensor-read-arg->mangle-str args))))


(defn build-bind-map
  "The special cases in the bind map must relate to the name mangling
  of the function"
  [tensor-var-map]
  (->> tensor-var-map
       (map (fn [[tensor variable]]
              (let [shape (ct/shape tensor)]
                [variable (api/declare-buffer
                           (:shape variable)
                           :dtype (name (ct/get-datatype tensor))
                           :name (or (:name variable) "unnamed")
                           :strides (if (ct/dense? tensor)
                                      nil
                                      (mapv #(api/variable (str "_stride_" %)
                                                           :dtype "int32")
                                            (clojure.core/range (count shape))))
                           :elem-offset (if (dbuf/has-byte-offset? tensor)
                                          (api/variable "_elem_offset")
                                          nil))])))
       (into {})))


(defn get-or-create-fn
  [stream fn-name fn-create-fn]
  (let [device-type (tvm-reg/device-type stream)
        device-id (tvm-reg/device-id stream)]
    (if-let [retval (get @*fn-map* [device-type device-id fn-name])]
      retval
      (let [retval (fn-create-fn)]
        (swap! *fn-map* assoc fn-name retval)
        (resource/track (->PRemoveFunction device-type device-id fn-name))
        retval))))


(def device-datatype-map
  "https://github.com/dmlc/tvm/issues/984"
  {:uint8 :uint32
   :int8 :int32
   :uint16 :uint32
   :int16 :int32
   :uint64 :int64})


(defn- get-scalar-datatype
  [device fn-datatype]
  (if (tvm-reg/device-datatypes? device)
    (get device-datatype-map fn-datatype fn-datatype)
    fn-datatype))


(defn n-dim-compute-op
  [n-dims compute-fn & {:keys [name]
                        :or {name "compute_op"}}]
  ;;Result shape has n-dims
  (api/compute (->> (range n-dims)
                    (mapv (fn [idx]
                            (api/variable (str name "_i" idx)))))
               (y-dim-tvm-fn n-dims compute-fn)
               name))


(defn n-dims->shape-stride-tuples
  [n-dims arg-name]
  (->> (range n-dims)
       (mapv (fn [idx]
               [(api/variable (str arg-name "_shape_" idx) :dtype "int32")
                (api/variable (str arg-name "_stride_" idx) :dtype "int32")]))))


(defn tensor-read-placeholder
  [tensor arg-name]
  (api/placeholder [(api/variable "_tens_ecount")] arg-name
                   :dtype (name (ct/get-datatype tensor))))


(defn tensor-read-dims->vars
  [n-dims tensor arg-name]
  (when-not (<= (count (ct/shape tensor)) n-dims)
    (throw (ex-info "Read tensor can only equal or scatter into write tensor"
                    {:write-tensor-n-dims n-dims
                     :read-tensor-n-dims (count (ct/shape tensor))})))

  {:placeholder (tensor-read-placeholder tensor arg-name)
   :shape-stride-tuples (n-dims->shape-stride-tuples n-dims arg-name)})


(defn tensor-read
  "Perform the math required to get the absolute element offset from the shape/stride combined with the max shape variables"
  [placeholder index-vars shape-stride-tuples]
  (when-not (= (count index-vars)
               (count shape-stride-tuples))
    (throw (ex-info "Count of index vars must count of tensor shape"
                    {:index-var-count (count index-vars)
                     :shape-count (count shape-stride-tuples)})))
  ;;Generic broadcasting reduction for the destination indexes into any argument's shape.
  (api/tget placeholder
            [(->> (map (fn [index-var [shape stride]]
                         (api/mul stride
                                  (api/mod index-var shape)))
                       index-vars shape-stride-tuples)
                  (reduce api/add))]))


(defn left-pad-ones
  [shape-vec n-dims]
  (concat (repeat (- (long n-dims) (count shape-vec)) 1)
          shape-vec))


(defn explode-read-tensor
  [tensor n-dims]
  (let [tens-shape (left-pad-ones (ct/shape tensor) n-dims)
        tens-stride (ct-dims/extend-strides tens-shape (ct/strides tensor))]
    ;;Read tensors pass in their backing store so that we have generic broadcasting rules to effect.
    (concat [(ct/tensor->buffer tensor)]
            (map int tens-shape)
            (map int tens-stride))))


(defn- compile-operation
  [driver fn-name compute-op arglist bind-map]
  (let [schedule (api/create-schedule compute-op)
        _ (tvm-reg/schedule-injective! driver schedule compute-op {})
        mod-fns (tvm-reg/->module driver [{:schedule schedule
                                           :name fn-name
                                           :arglist arglist
                                           :bind-map (build-bind-map bind-map)}])]
    (get-in mod-fns [:fn-map fn-name])))




(defn assign-constant!
  [stream tensor value]
  (let [tensor (if (ct/dense? tensor)
                 (ct/as-vector tensor)
                 tensor)
        datatype (ct/get-datatype tensor)
        fn-name (keyword (get-fn-name "assign_constant" tensor))
        scalar-datatype (get-scalar-datatype (drv/get-driver stream) datatype)
        assign-fn (get-or-create-fn
                   stream fn-name
                   #(let [const-var (api/variable "const_val"
                                                  :dtype (name scalar-datatype))
                          compute-op (n-dim-compute-op (count (ct/shape tensor))
                                                       (fn [& args]
                                                         (if (= scalar-datatype datatype)
                                                           const-var
                                                           (api/static-cast
                                                            (name datatype)
                                                            const-var))))
                          result (first (api/output-tensors compute-op))]
                      (compile-operation (drv/get-driver stream)
                                         fn-name compute-op
                                         [result const-var]
                                         {tensor result})))]
    (tvm-reg/call-function stream assign-fn tensor (dtype/cast value scalar-datatype))))


(defn assign!
  "Broadcasting, marshalling assignment of rhs to lhs
lhs = rhs"
  [stream lhs rhs]
  (let [lhs-dtype (ct/get-datatype lhs)
        rhs-dtype (ct/get-datatype rhs)
        fn-name (keyword (get-fn-name "assign" lhs rhs))
        dest-datatype (ct/get-datatype lhs)
        n-dims (count (ct/shape lhs))
        max-shape (ct/shape lhs)
        assign-fn
        (get-or-create-fn
         stream fn-name
         #(let [
                ;;Ignoring the fact the the shape at any index *could* be an array of data instead of
                ;;an integer...
                {rhs-placeholder :placeholder
                 rhs-shape-stride-tuples :shape-stride-tuples} (tensor-read-dims->vars n-dims rhs "rhs")
                compute-op (n-dim-compute-op
                            (count (ct/shape lhs))
                            (fn [index-vars]
                              (api/static-cast (name dest-datatype)
                                               (tensor-read rhs-placeholder
                                                            index-vars
                                                            rhs-shape-stride-tuples))))
                result (first (api/output-tensors compute-op))]
            (compile-operation (drv/get-driver stream)
                               fn-name compute-op
                               (->> (concat [result]
                                            [rhs-placeholder]
                                            (map first rhs-shape-stride-tuples)
                                            (map second rhs-shape-stride-tuples))
                                    vec)
                               {lhs result})))]
    (apply tvm-reg/call-function
           stream assign-fn lhs (explode-read-tensor rhs (count max-shape)))))


(extend-protocol tm/TensorMath
  CPUStream
  (assign-constant! [stream tensor value]
    (assign-constant! stream tensor value))
  (assign! [stream lhs rhs]
    (assign! stream lhs rhs))

  GPUStream
  (assign-constant! [stream tensor value]
    (assign-constant! stream tensor value))
  (assign! [stream lhs rhs]
    (assign! stream lhs rhs)))


(defn device-buffer->tensor
  [dev-buf dimensions]
  (ct/construct-tensor dimensions
                       dev-buf))


(defn typed-pointer->tensor
  "Convert a typed pointer into a tensor.  Force cpu will always
  convert the typed pointer into a tvm host buffer (which is also
  a cpu device buffer)."
  [typed-ptr & {:keys [force-cpu?]}]
  (let [target-device-kwd (if force-cpu?
                            :cpu
                            (-> (drv/get-device ct/*stream*)
                                tvm-reg/device-type-kwd))]
    (if (= :cpu target-device-kwd)
      ;;For cpu, we have a direct, zero-copy conversion.  We can read/write directly to
      ;;opencv's data storage
      (-> (cpu/ptr->device-buffer typed-ptr)
          (device-buffer->tensor (ct-dims/dimensions (m/shape typed-ptr))))

      ;;Copy the matrix onto the device
      (ct/->tensor typed-ptr :datatype (dtype/get-datatype typed-ptr)))))
