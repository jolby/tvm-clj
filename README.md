# tvm-clj

Clojure bindings and exploration of the [tvm](https://github.com/dmlc/tvm) library, part of the [dmlc](https://github.com/dmlc) ecosystem.


[![Clojars Project](https://img.shields.io/clojars/v/tvm-clj.svg)](https://clojars.org/tvm-clj)


## Justification

[tvm](https://github.com/dmlc/tvm) a system for dynamically generating high performance numeric code with backends for cpu, cuda, opencl, opengl, webassembly, vulcan, and verilog.  It has frontends mainly in python and c++ with a clear and well designed C-ABI that not only aids in the implementation of their python interface, but it also eases the binding into other language ecosystems such as the jvm and node.

tvm leverages [Halide](http://halide-lang.org).  Halide takes algorithms structured in specific ways and allows performance experimentation without affecting the output of the core algorithm.  A very solid justification for this is nicely put in these [slides](http://stellar.mit.edu/S/course/6/sp15/6.815/courseMaterial/topics/topic2/lectureNotes/14_Halide_print/14_Halide_print.pdf).  A Ph. D. was minted [here](http://people.csail.mit.edu/jrk/jrkthesis.pdf).  We also recommend watching the youtube [video](https://youtu.be/3uiEyEKji0M).


## Goals

1.  Learn about Halide and tvm and enable very clear and simple exploration of the system in clojure.  Make clojure a first class language in the dmlc ecosystem.
1.  Provide the tvm team with clear feedback and a second external implementation or a language binding on top of the C-ABI.
1.  Encourage wider adoption and exploration in terms of numerical programming; for instance a new implementation of J that carries the properties of a clojure or clojurescript ecosystem but includes all of the major concepts of J.  This would enable running some subset of J (or APL) programs (or functions) that are now far more optimized mode than before and accessible from node.js or the jvm.  It would also inform the wider discussion on numeric programming languages such as MatLab, TensorFlow, numpy, etc.
1.  Provide richer platform for binding to nnvm so that running existing networks via clojure is as seamless as possible.


## What, Concretely, Are You Talking About?


### Simple Example

tvm exposes a directed graph along with a declarative scheduling system to build high performance numerical systems for n-dimensional data.  In the example below, we dynamically create a function to add 2 vectors then compile that function for a cpu and gpu backend.  Note that the major difference between the backends lies in the scheduling; not in the algorithm itself.
[source](test/tvm_clj/api_test.clj)


### Vector Math Compiler Example

Built a small compiler that takes a statement of vector math and compiles to tvm.  This is extremely incomplete and not very efficient in terms of what is possible but
shows a vision of doing potentially entire neural network functions.

```
hand-coded java took:  "Elapsed time: 558.662639 msecs"

produce bgr_types_op {
  parallel (chan, 0, min(n_channels, 3)) {
    for (y.outer, 0, ((image_height + 31)/32)) {
      for (x.outer, 0, ((image_width + 31)/32)) {
        for (y.inner, 0, 32) {
          if (likely(((y.outer*32) < (image_height - y.inner)))) {
            for (x.inner.s, 0, 32) {
              if (likely(((x.outer*32) < (image_width - x.inner.s)))) {
                buffer[(((x.outer*32) + ((((chan*image_height) + (y.outer*32)) + y.inner)*image_width)) + x.inner.s)] = ((float32(buffer[((((((x.outer*32) + (((y.outer*32) + y.inner)*image_width)) + x.inner.s)*n_channels) - chan) + 2)])*0.003922f) + -0.500000f)
              }
            }
          }
        }
      }
    }
  }
}

Compiled (cpu) tensor took: "Elapsed time: 31.712205 msecs"

produce bgr_types_op {
  // attr [iter_var(blockIdx.z, , blockIdx.z)] thread_extent = min(n_channels, 3)
  // attr [iter_var(blockIdx.y, , blockIdx.y)] thread_extent = ((image_height + 31)/32)
  // attr [iter_var(blockIdx.x, , blockIdx.x)] thread_extent = ((image_width + 31)/32)
  // attr [iter_var(threadIdx.y, , threadIdx.y)] thread_extent = 32
  // attr [iter_var(threadIdx.x, , threadIdx.x)] thread_extent = 32
  if (likely(((blockIdx.y*32) < (image_height - threadIdx.y)))) {
    if (likely(((blockIdx.x*32) < (image_width - threadIdx.x)))) {
      buffer[(((blockIdx.x*32) + ((((blockIdx.z*image_height) + (blockIdx.y*32)) + threadIdx.y)*image_width)) + threadIdx.x)] = ((float32(buffer[((((((blockIdx.x*32) + (((blockIdx.y*32) + threadIdx.y)*image_width)) + threadIdx.x)*n_channels) - blockIdx.z) + 2)])*0.003922f) + -0.500000f)
    }
  }
}

Compiled (opencl) tensor took: "Elapsed time: 4.641527 msecs"
```
[source](test/tech/compute/tvm/compile_test.clj)


### Image Scaling (TVM vs OpenCV)

Faster (and correct) bilinear and area filtering.  Handily beats opencv::resize on a
desktop compute in both speed and code readability.

```clojure
;; cpu, algorithm run 10 times.  Desktop (NVIDIA 1070):

tvm-clj.image.resize-test> (downsample-img)
{:opencv-area-time "\"Elapsed time: 815.136235 msecs\"\n",
 :opencv-bilinear-time "\"Elapsed time: 220.774128 msecs\"\n",
 :tvm-area-time "\"Elapsed time: 380.640778 msecs\"\n",
 :tvm-bilinear-time "\"Elapsed time: 21.361915 msecs\"\n"}

tvm-clj.image.resize-test> (downsample-img :device-type :opencl)
{:opencv-area-time "\"Elapsed time: 338.918811 msecs\"\n",
 :opencv-bilinear-time "\"Elapsed time: 16.837844 msecs\"\n",
 :tvm-area-time "\"Elapsed time: 31.076962 msecs\"\n",
 :tvm-bilinear-time "\"Elapsed time: 3.033296 msecs\"\n"}

;;Laptop times
tvm-clj.image.resize-test> (downsample-img)
{:opencv-area-time "\"Elapsed time: 2422.879178 msecs\"\n",
 :opencv-bilinear-time "\"Elapsed time: 637.622425 msecs\"\n",
 :tvm-area-time "\"Elapsed time: 333.946424 msecs\"\n",
 :tvm-bilinear-time "\"Elapsed time: 20.585665 msecs\"\n"}

tvm-clj.image.resize-test> (downsample-img :device-type :opencl)
{:opencv-area-time "\"Elapsed time: 2460.51718 msecs\"\n",
 :opencv-bilinear-time "\"Elapsed time: 667.624091 msecs\"\n",
 :tvm-area-time "\"Elapsed time: 315.864799 msecs\"\n",
 :tvm-bilinear-time "\"Elapsed time: 16.290168 msecs\"\n"}
```


tvm-area: ![tvm-results](docs/images/test.jpg)


opencv-bilinear: ![opencv-results](docs/images/ref.jpg)


* [tvm-clj source](src/tech/compute/tvm/image/resize.clj)
* [opencv source](https://github.com/opencv/opencv/blob/master/modules/imgproc/src/resize.cpp)


## Getting all the source

At top level:
```bash
git submodule update --init --recursive
```

## Building the TVM java bindings

```bash
sudo apt install make g++ cmake llvm-dev libopenblas-dev

## Cuda support
sudo apt install  nvidia-cuda-toolkit

## opencl support (nvidia-cuda includes this)
sudo apt install ocl-icd-* opencl-headers

## intel graphics adapter support
sudo apt install beignet beignet-opencl-icd

pushd tvm


mkdir build
cp cmake/make.config build
pushd build

## now edit tvm/build/config.cmake to appropriate for your system. I have
## tested openblas cuda, opencl.
cmake ..

make -j8
popd
popd

scripts/build-jni.sh
```


At this point you should have the bindings under `java/tvm_clj/tvm/runtime/java` and a couple native libraries
under the `java/native/linux/x86_64` pathway.

Building a jar or uberjar will package all of these things into a good place.


## License


Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
